package com.payflow.service;

import com.payflow.exception.InvalidSignatureException;
import com.payflow.gateway.WebhookSignatureVerifier;
import com.payflow.model.Order;
import com.payflow.model.Payment;
import com.payflow.repository.InMemoryRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * POST /api/webhooks/razorpay
 * 1) verify HMAC signature, 2) de-duplicate by event id (Razorpay retries deliveries),
 * 3) apply the state change through the same idempotent transitions as the capture API.
 * Payload format here is simplified to key=value&key=value instead of JSON to stay dependency-free.
 */
public class WebhookService {
    private final WebhookSignatureVerifier verifier;
    private final PaymentService paymentService;
    private final InMemoryRepository<Order> orders;
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    public WebhookService(WebhookSignatureVerifier verifier, PaymentService paymentService, InMemoryRepository<Order> orders) {
        this.verifier = verifier;
        this.paymentService = paymentService;
        this.orders = orders;
    }

    /** @return a short status string, standing in for the HTTP response. */
    public String handle(String rawBody, String signatureHeader) {
        if (!verifier.verify(rawBody, signatureHeader)) throw new InvalidSignatureException("Invalid webhook signature");

        Map<String, String> event = parse(rawBody);
        String eventId = event.get("event_id");
        if (!processedEvents.add(eventId)) return "200 duplicate event " + eventId + " ignored";

        String gatewayPaymentId = event.get("payment_id");
        String gatewayOrderId = event.get("order_id");
        Payment payment = paymentService.findByGatewayPaymentId(gatewayPaymentId).orElseGet(() -> {
            // Webhook arrived before the client called /capture: create the payment from the order.
            Order order = orders.findAll(o -> o.getGatewayOrderId().equals(gatewayOrderId)).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unknown gateway order " + gatewayOrderId));
            return paymentService.findOrCreatePayment(order, gatewayPaymentId);
        });

        switch (event.get("event")) {
            case "payment.captured" -> paymentService.markCaptured(payment.getId());
            case "payment.failed"   -> paymentService.markFailed(payment.getId());
            default -> { return "200 event type ignored"; }
        }
        return "200 processed " + eventId;
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) m.put(kv[0], kv[1]);
        }
        return m;
    }
}
