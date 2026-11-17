package com.payflow.model;

import java.time.Instant;

public class Order implements Versioned<Order> {
    private final String id;
    private final String customerId;
    private final long amount;          // minor units (paise)
    private final String currency;
    private final String gatewayOrderId;
    private final Instant createdAt;
    private OrderStatus status;
    private long version;

    public Order(String id, String customerId, long amount, String currency, String gatewayOrderId) {
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.gatewayOrderId = gatewayOrderId;
        this.createdAt = Instant.now();
        this.status = OrderStatus.CREATED;
    }

    private Order(Order o) {
        this.id = o.id; this.customerId = o.customerId; this.amount = o.amount;
        this.currency = o.currency; this.gatewayOrderId = o.gatewayOrderId;
        this.createdAt = o.createdAt; this.status = o.status; this.version = o.version;
    }

    @Override public Order copy() { return new Order(this); }
    @Override public String getId() { return id; }
    @Override public long getVersion() { return version; }
    @Override public void setVersion(long version) { this.version = version; }
    public String getCustomerId() { return customerId; }
    public long getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getGatewayOrderId() { return gatewayOrderId; }
    public Instant getCreatedAt() { return createdAt; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    @Override public String toString() {
        return "Order[" + id + ", " + customerId + ", " + amount + " " + currency + ", " + status + ", v" + version + "]";
    }
}
