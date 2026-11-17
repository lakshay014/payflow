package com.payflow.service;

import com.payflow.exception.OptimisticLockException;
import com.payflow.exception.PayFlowException;
import com.payflow.gateway.GatewayResult;
import com.payflow.gateway.PaymentGateway;
import com.payflow.model.*;
import com.payflow.repository.InMemoryRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * POST /api/payments/capture and the shared state transitions used by the capture API,
 * the webhook handler and the reconciliation job. All transitions are idempotent and
 * protected by optimistic locking, so concurrent paths can never double-capture.
 */
public class PaymentService {
    private static final int LOCK_RETRIES = 5;

    private final InMemoryRepository<Order> orders;
    private final InMemoryRepository<Payment> payments;
    private final PaymentGateway gateway;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    public PaymentService(InMemoryRepository<Order> orders, InMemoryRepository<Payment> payments,
                          PaymentGateway gateway, LedgerService ledger, IdempotencyService idempotency) {
        this.orders = orders;
        this.payments = payments;
        this.gateway = gateway;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    /** Called by the client after Razorpay Checkout returns a razorpay_payment_id. */
    public Payment capture(String idempotencyKey, String orderId, String gatewayPaymentId) {
        String fingerprint = orderId + "|" + gatewayPaymentId;
        return idempotency.execute("capture", idempotencyKey, fingerprint, () -> {
            Order order = orders.findById(orderId).orElseThrow(() -> new PayFlowException("Order not found: " + orderId));
            if (order.getStatus() == OrderStatus.FAILED) throw new PayFlowException("Order " + orderId + " has failed");
            Payment payment = findOrCreatePayment(order, gatewayPaymentId);
            if (payment.getStatus() != PaymentStatus.PENDING) return payment;   // already captured / failed
            return attemptCapture(payment.getId());
        });
    }

    /** One capture attempt at the gateway. Failures leave the payment PENDING for the retry job. */
    Payment attemptCapture(String paymentId) {
        Payment p = update(paymentId, pay -> { pay.incrementAttempts(); return true; });
        if (p.getStatus() != PaymentStatus.PENDING) return p;
        GatewayResult result = gateway.capturePayment(p.getGatewayPaymentId(), p.getAmount());
        if (result.success()) return markCaptured(paymentId);
        System.out.println("    capture attempt " + p.getAttempts() + " for " + paymentId + " failed: " + result.error());
        return payments.findById(paymentId).orElseThrow();
    }

    /** PENDING -> CAPTURED, order -> PAID, ledger posting. Safe to call many times from many places. */
    public Payment markCaptured(String paymentId) {
        Payment p = update(paymentId, pay -> {
            if (pay.getStatus() == PaymentStatus.CAPTURED) return false;      // no-op, already done
            if (pay.getStatus() == PaymentStatus.FAILED) {
                System.out.println("    WARNING: gateway captured " + paymentId + " after we marked it FAILED; correcting");
            }
            pay.setStatus(PaymentStatus.CAPTURED);
            return true;
        });
        updateOrder(p.getOrderId(), OrderStatus.PAID);
        // Ledger posting keyed by payment id: posted exactly once even if this method runs twice.
        ledger.post("capture:" + paymentId, LedgerService.GATEWAY_SETTLEMENT, LedgerService.MERCHANT_REVENUE, p.getAmount());
        return p;
    }

    public Payment markFailed(String paymentId) {
        Payment p = update(paymentId, pay -> {
            if (pay.getStatus() != PaymentStatus.PENDING) return false;
            pay.setStatus(PaymentStatus.FAILED);
            return true;
        });
        if (p.getStatus() == PaymentStatus.FAILED) updateOrder(p.getOrderId(), OrderStatus.FAILED);
        return p;
    }

    Payment findOrCreatePayment(Order order, String gatewayPaymentId) {
        Optional<Payment> existing = payments.findById("pay_" + order.getId().substring(4));
        if (existing.isPresent()) {
            Payment p = existing.get();
            if (gatewayPaymentId != null && p.getGatewayPaymentId() != null
                    && !p.getGatewayPaymentId().equals(gatewayPaymentId)) {
                throw new PayFlowException("Order " + order.getId() + " already has payment " + p.getGatewayPaymentId());
            }
            return p;
        }
        // Payment id is derived from the order id, so the primary key acts as a unique constraint:
        // two concurrent requests cannot both create a payment for the same order.
        Payment p = new Payment("pay_" + order.getId().substring(4), order.getId(), order.getAmount());
        p.setGatewayPaymentId(gatewayPaymentId);
        try {
            return payments.insert(p);
        } catch (OptimisticLockException e) {
            return findByOrder(order.getId()).orElseThrow();
        }
    }

    public Optional<Payment> findByOrder(String orderId) {
        return payments.findAll(p -> p.getOrderId().equals(orderId)).stream().findFirst();
    }

    public Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId) {
        return payments.findAll(p -> gatewayPaymentId.equals(p.getGatewayPaymentId())).stream().findFirst();
    }

    public Payment get(String paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> new PayFlowException("Payment not found: " + paymentId));
    }

    public List<Payment> findPending() {
        return payments.findAll(p -> p.getStatus() == PaymentStatus.PENDING);
    }

    public List<Payment> findCaptured() {
        return payments.findAll(p -> p.getStatus() == PaymentStatus.CAPTURED);
    }

    /** Read-modify-write with optimistic-lock retry. The mutator returns false when no change is needed. */
    Payment update(String paymentId, Function<Payment, Boolean> mutator) {
        for (int i = 0; i < LOCK_RETRIES; i++) {
            Payment p = get(paymentId);
            if (!mutator.apply(p)) return p;
            try {
                return payments.save(p);
            } catch (OptimisticLockException e) {
                System.out.println("    optimistic lock conflict on " + paymentId + ", retrying");
            }
        }
        throw new PayFlowException("Could not update payment " + paymentId + " after " + LOCK_RETRIES + " attempts");
    }

    void updateOrder(String orderId, OrderStatus status) {
        for (int i = 0; i < LOCK_RETRIES; i++) {
            Order o = orders.findById(orderId).orElseThrow();
            if (o.getStatus() == status) return;
            o.setStatus(status);
            try { orders.save(o); return; } catch (OptimisticLockException ignored) { }
        }
        throw new PayFlowException("Could not update order " + orderId);
    }

    static Comparator<Transaction> byTime() { return Comparator.comparing(Transaction::at); }
}
