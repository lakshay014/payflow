package com.payflow.gateway;

/** Abstraction over Razorpay's Orders / Payments / Refunds APIs. */
public interface PaymentGateway {
    GatewayResult createOrder(String receipt, long amount, String currency);
    GatewayResult capturePayment(String gatewayPaymentId, long amount);
    GatewayResult refund(String gatewayPaymentId, long amount);
    /** Returns "captured", "failed" or "pending" for reconciliation. */
    String fetchPaymentStatus(String gatewayPaymentId);
}
