package com.payflow.service;

import com.payflow.exception.PayFlowException;
import com.payflow.gateway.GatewayResult;
import com.payflow.gateway.PaymentGateway;
import com.payflow.model.Order;
import com.payflow.repository.InMemoryRepository;

import java.util.UUID;

/** POST /api/orders */
public class OrderService {
    private final InMemoryRepository<Order> orders;
    private final PaymentGateway gateway;
    private final IdempotencyService idempotency;

    public OrderService(InMemoryRepository<Order> orders, PaymentGateway gateway, IdempotencyService idempotency) {
        this.orders = orders;
        this.gateway = gateway;
        this.idempotency = idempotency;
    }

    public Order createOrder(String idempotencyKey, String customerId, long amount, String currency) {
        if (amount <= 0) throw new PayFlowException("Amount must be positive");
        String fingerprint = customerId + "|" + amount + "|" + currency;
        return idempotency.execute("create-order", idempotencyKey, fingerprint, () -> {
            String id = "ord_" + UUID.randomUUID().toString().substring(0, 8);
            GatewayResult gw = gateway.createOrder(id, amount, currency);
            if (!gw.success()) throw new PayFlowException("Gateway order creation failed: " + gw.error());
            return orders.save(new Order(id, customerId, amount, currency, gw.reference()));
        });
    }

    public Order get(String orderId) {
        return orders.findById(orderId).orElseThrow(() -> new PayFlowException("Order not found: " + orderId));
    }
}
