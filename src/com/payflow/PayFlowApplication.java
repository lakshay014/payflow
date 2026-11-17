package com.payflow;

import com.payflow.exception.OptimisticLockException;
import com.payflow.exception.PayFlowException;
import com.payflow.gateway.MockRazorpayGateway;
import com.payflow.gateway.WebhookSignatureVerifier;
import com.payflow.model.*;
import com.payflow.repository.InMemoryRepository;
import com.payflow.service.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Wires the components together (what Spring would do) and runs through every flow. */
public class PayFlowApplication {
    static int checks = 0, failures = 0;

    public static void main(String[] args) throws Exception {
        // ---- wiring ----
        InMemoryRepository<Order> orderRepo = new InMemoryRepository<>();
        InMemoryRepository<Payment> paymentRepo = new InMemoryRepository<>();
        MockRazorpayGateway gateway = new MockRazorpayGateway();
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("whsec_demo_secret");
        IdempotencyService idem = new IdempotencyService();
        LedgerService ledger = new LedgerService();
        OrderService orderService = new OrderService(orderRepo, gateway, idem);
        PaymentService paymentService = new PaymentService(orderRepo, paymentRepo, gateway, ledger, idem);
        RefundService refundService = new RefundService(paymentService, gateway, ledger, idem);
        WebhookService webhookService = new WebhookService(verifier, paymentService, orderRepo);
        ReconciliationService recon = new ReconciliationService(paymentService, gateway, ledger, Duration.ofMillis(50));
        TransactionHistoryService history = new TransactionHistoryService(orderRepo, paymentService, refundService);

        // ---- 1. Order creation + idempotency ----
        section("1. Create order (same Idempotency-Key sent twice)");
        Order o1 = orderService.createOrder("key-order-1", "cust_42", 50_000, "INR");
        Order o1Again = orderService.createOrder("key-order-1", "cust_42", 50_000, "INR");
        System.out.println("  " + o1);
        check("retry returns the same order", o1.getId().equals(o1Again.getId()));
        expectError("same key, different body is rejected",
                () -> orderService.createOrder("key-order-1", "cust_42", 99_999, "INR"));

        // ---- 2. Capture + duplicate capture ----
        section("2. Capture payment, then client retries the capture");
        Payment p1 = paymentService.capture("key-cap-1", o1.getId(), "pay_GW001");
        Payment p1Again = paymentService.capture("key-cap-1", o1.getId(), "pay_GW001");
        System.out.println("  " + p1);
        check("payment CAPTURED", p1.getStatus() == PaymentStatus.CAPTURED);
        check("retry returns same payment", p1.getId().equals(p1Again.getId()));
        check("order PAID", orderService.get(o1.getId()).getStatus() == OrderStatus.PAID);
        check("ledger has exactly 2 entries (1 debit + 1 credit)", ledger.entries().size() == 2);

        // ---- 3. Concurrent captures on a new order ----
        section("3. Ten concurrent capture requests for the same order (different keys)");
        Order o2 = orderService.createOrder("key-order-2", "cust_42", 20_000, "INR");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Payment>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String key = "key-cap-2-" + i;
            futures.add(pool.submit(() -> paymentService.capture(key, o2.getId(), "pay_GW002")));
        }
        Set<String> ids = new HashSet<>();
        for (Future<Payment> f : futures) ids.add(f.get().getId());
        pool.shutdown();
        check("all requests resolved to one payment", ids.size() == 1);
        check("ledger posted once for the order (4 entries total)", ledger.entries().size() == 4);

        // ---- 4. Webhooks ----
        section("4. Webhooks: arrives before client capture, duplicate delivery, forged signature");
        Order o3 = orderService.createOrder("key-order-3", "cust_7", 15_000, "INR");
        String body = "event_id=evt_1&event=payment.captured&payment_id=pay_GW003&order_id=" + o3.getGatewayOrderId();
        System.out.println("  " + webhookService.handle(body, verifier.sign(body)));
        System.out.println("  " + webhookService.handle(body, verifier.sign(body)));
        check("webhook captured the payment", paymentService.findByOrder(o3.getId()).orElseThrow().getStatus() == PaymentStatus.CAPTURED);
        Payment late = paymentService.capture("key-cap-3", o3.getId(), "pay_GW003");
        check("client's late /capture call is a no-op", late.getStatus() == PaymentStatus.CAPTURED && ledger.entries().size() == 6);
        String forged = "event_id=evt_2&event=payment.captured&payment_id=pay_X&order_id=order_X";
        expectError("forged signature rejected", () -> webhookService.handle(forged, "deadbeef"));

        // ---- 5. Refunds ----
        section("5. Partial refund, over-refund, final refund");
        Refund r1 = refundService.refund("key-ref-1", p1.getId(), 20_000);
        Refund r1Again = refundService.refund("key-ref-1", p1.getId(), 20_000);
        check("refund retry returns same refund", r1.id().equals(r1Again.id()));
        check("order PARTIALLY_REFUNDED", orderService.get(o1.getId()).getStatus() == OrderStatus.PARTIALLY_REFUNDED);
        expectError("over-refund rejected", () -> refundService.refund("key-ref-2", p1.getId(), 40_000));
        refundService.refund("key-ref-3", p1.getId(), 30_000);
        check("order REFUNDED", orderService.get(o1.getId()).getStatus() == OrderStatus.REFUNDED);
        check("refundable amount now 0", paymentService.get(p1.getId()).getRefundableAmount() == 0);

        // ---- 6. Optimistic locking ----
        section("6. Optimistic locking: two writers hold the same version");
        Order copyA = orderService.get(o2.getId());
        Order copyB = orderService.get(o2.getId());
        copyA.setStatus(OrderStatus.REFUNDED);
        orderRepo.save(copyA);
        copyB.setStatus(OrderStatus.FAILED);
        try { orderRepo.save(copyB); check("stale write rejected", false); }
        catch (OptimisticLockException e) { System.out.println("  " + e.getMessage()); check("stale write rejected", true); }
        copyA.setStatus(OrderStatus.PAID); orderRepo.save(copyA);   // restore

        // ---- 7. Retries & reconciliation ----
        section("7. Scheduled retry + reconciliation");
        Order o4 = orderService.createOrder("key-order-4", "cust_7", 10_000, "INR");
        gateway.failNextCaptures("pay_GW004", 1);                   // transient error, then OK
        Payment p4 = paymentService.capture("key-cap-4", o4.getId(), "pay_GW004");
        System.out.println("  after API call: " + p4.getStatus());

        Order o5 = orderService.createOrder("key-order-5", "cust_7", 5_000, "INR");
        gateway.simulateTimeoutAfterCapture("pay_GW005");           // money taken, our call timed out
        Payment p5 = paymentService.capture("key-cap-5", o5.getId(), "pay_GW005");
        System.out.println("  after API call: " + p5.getStatus());

        Order o6 = orderService.createOrder("key-order-6", "cust_9", 8_000, "INR");
        gateway.failNextCaptures("pay_GW006", 10);                  // gateway keeps failing
        paymentService.capture("key-cap-6", o6.getId(), "pay_GW006");

        recon.start(Duration.ofMillis(100));                        // background scheduler
        Thread.sleep(1_000);
        recon.stop();

        check("transient failure recovered by retry", paymentService.get(p4.getId()).getStatus() == PaymentStatus.CAPTURED);
        check("timed-out capture synced from gateway", paymentService.get(p5.getId()).getStatus() == PaymentStatus.CAPTURED);
        Payment p6 = paymentService.findByOrder(o6.getId()).orElseThrow();
        check("permanent failure marked FAILED after " + ReconciliationService.MAX_ATTEMPTS + " attempts",
                p6.getStatus() == PaymentStatus.FAILED && p6.getAttempts() == ReconciliationService.MAX_ATTEMPTS);
        check("order of failed payment is FAILED", orderService.get(o6.getId()).getStatus() == OrderStatus.FAILED);

        // ---- 8. Ledger & history ----
        section("8. Ledger and transaction history");
        System.out.printf("  %-28s %10d%n", LedgerService.GATEWAY_SETTLEMENT, ledger.balance(LedgerService.GATEWAY_SETTLEMENT));
        System.out.printf("  %-28s %10d%n", LedgerService.MERCHANT_REVENUE, ledger.balance(LedgerService.MERCHANT_REVENUE));
        check("ledger balanced (debits == credits)", ledger.isBalanced());
        long expectedNet = 20_000 + 15_000 + 10_000 + 5_000;         // o1 fully refunded, o6 failed
        check("settlement balance = net captured (" + expectedNet + ")", ledger.balance(LedgerService.GATEWAY_SETTLEMENT) == expectedNet);
        for (Transaction t : history.forCustomer("cust_42"))
            System.out.printf("  %-8s %-12s %-12s %8d  %s%n", t.type(), t.referenceId(), t.orderId(), t.amount(), t.status());

        System.out.println("\n" + (checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    static void section(String title) { System.out.println("\n== " + title + " =="); }

    static void check(String name, boolean ok) {
        checks++;
        if (!ok) failures++;
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name);
    }

    static void expectError(String name, Runnable r) {
        try { r.run(); check(name, false); }
        catch (PayFlowException e) { System.out.println("  rejected: " + e.getMessage()); check(name, true); }
    }
}
