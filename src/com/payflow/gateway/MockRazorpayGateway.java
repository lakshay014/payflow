package com.payflow.gateway;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated Razorpay. Swap for a real client (razorpay-java SDK) in production.
 * Supports injecting transient failures so retry/reconciliation can be demonstrated.
 */
public class MockRazorpayGateway implements PaymentGateway {
    private final Map<String, String> paymentStatus = new ConcurrentHashMap<>();
    private final Map<String, Integer> failuresRemaining = new ConcurrentHashMap<>();
    private final Set<String> timeouts = ConcurrentHashMap.newKeySet();

    /** Next {@code count} capture calls for this payment fail with a network error. */
    public void failNextCaptures(String gatewayPaymentId, int count) { failuresRemaining.put(gatewayPaymentId, count); }

    /** Capture succeeds at the gateway but our call "times out", leaving our record PENDING. */
    public void simulateTimeoutAfterCapture(String gatewayPaymentId) { timeouts.add(gatewayPaymentId); }

    @Override public GatewayResult createOrder(String receipt, long amount, String currency) {
        return GatewayResult.ok("order_" + shortId(), "created");
    }

    @Override public GatewayResult capturePayment(String gatewayPaymentId, long amount) {
        int left = failuresRemaining.getOrDefault(gatewayPaymentId, 0);
        if (left > 0) {
            failuresRemaining.put(gatewayPaymentId, left - 1);
            return GatewayResult.fail("GATEWAY_TIMEOUT");
        }
        paymentStatus.put(gatewayPaymentId, "captured");
        if (timeouts.remove(gatewayPaymentId)) return GatewayResult.fail("READ_TIMEOUT");
        return GatewayResult.ok(gatewayPaymentId, "captured");
    }

    @Override public GatewayResult refund(String gatewayPaymentId, long amount) {
        if (!"captured".equals(paymentStatus.get(gatewayPaymentId))) return GatewayResult.fail("PAYMENT_NOT_CAPTURED");
        return GatewayResult.ok("rfnd_" + shortId(), "processed");
    }

    @Override public String fetchPaymentStatus(String gatewayPaymentId) {
        return paymentStatus.getOrDefault(gatewayPaymentId, "pending");
    }

    private static String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 14); }
}
