package com.payflow.model;

import java.time.Instant;

public class Payment implements Versioned<Payment> {
    private final String id;
    private final String orderId;
    private final long amount;
    private final Instant createdAt;
    private String gatewayPaymentId;
    private PaymentStatus status;
    private long refundedAmount;
    private int attempts;
    private Instant updatedAt;
    private long version;

    public Payment(String id, String orderId, long amount) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    private Payment(Payment p) {
        this.id = p.id; this.orderId = p.orderId; this.amount = p.amount; this.createdAt = p.createdAt;
        this.gatewayPaymentId = p.gatewayPaymentId; this.status = p.status;
        this.refundedAmount = p.refundedAmount; this.attempts = p.attempts;
        this.updatedAt = p.updatedAt; this.version = p.version;
    }

    @Override public Payment copy() { return new Payment(this); }
    @Override public String getId() { return id; }
    @Override public long getVersion() { return version; }
    @Override public void setVersion(long version) { this.version = version; }
    public String getOrderId() { return orderId; }
    public long getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }
    public String getGatewayPaymentId() { return gatewayPaymentId; }
    public void setGatewayPaymentId(String id) { this.gatewayPaymentId = id; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public long getRefundedAmount() { return refundedAmount; }
    public void addRefunded(long amt) { this.refundedAmount += amt; this.updatedAt = Instant.now(); }
    public long getRefundableAmount() { return amount - refundedAmount; }
    public int getAttempts() { return attempts; }
    public void incrementAttempts() { this.attempts++; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override public String toString() {
        return "Payment[" + id + ", order=" + orderId + ", gw=" + gatewayPaymentId + ", " + amount
                + ", " + status + ", refunded=" + refundedAmount + ", attempts=" + attempts + ", v" + version + "]";
    }
}
