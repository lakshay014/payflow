package com.payflow.service;

import com.payflow.exception.PayFlowException;
import com.payflow.gateway.GatewayResult;
import com.payflow.gateway.PaymentGateway;
import com.payflow.model.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * POST /api/payments/{id}/refunds — full or partial refunds.
 * The refund amount is reserved on the payment first (optimistic lock), so two concurrent
 * refunds can never together exceed the captured amount. If the gateway rejects, the reservation is released.
 */
public class RefundService {
    private final PaymentService paymentService;
    private final PaymentGateway gateway;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();

    public RefundService(PaymentService paymentService, PaymentGateway gateway,
                         LedgerService ledger, IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.gateway = gateway;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    public Refund refund(String idempotencyKey, String paymentId, long amount) {
        if (amount <= 0) throw new PayFlowException("Refund amount must be positive");
        return idempotency.execute("refund", idempotencyKey, paymentId + "|" + amount, () -> {
            // 1. Reserve
            Payment p = paymentService.update(paymentId, pay -> {
                if (pay.getStatus() != PaymentStatus.CAPTURED)
                    throw new PayFlowException("Only captured payments can be refunded (status=" + pay.getStatus() + ")");
                if (amount > pay.getRefundableAmount())
                    throw new PayFlowException("Refund " + amount + " exceeds refundable amount " + pay.getRefundableAmount());
                pay.addRefunded(amount);
                return true;
            });

            // 2. Call gateway
            GatewayResult gw = gateway.refund(p.getGatewayPaymentId(), amount);
            if (!gw.success()) {
                paymentService.update(paymentId, pay -> { pay.addRefunded(-amount); return true; });
                throw new PayFlowException("Gateway refund failed: " + gw.error());
            }

            // 3. Record + ledger reversal + order status
            Refund refund = new Refund("rf_" + UUID.randomUUID().toString().substring(0, 8), paymentId,
                    gw.reference(), amount, Instant.now());
            refunds.add(refund);
            ledger.post("refund:" + refund.id(), LedgerService.MERCHANT_REVENUE, LedgerService.GATEWAY_SETTLEMENT, amount);
            paymentService.updateOrder(p.getOrderId(),
                    p.getRefundableAmount() == 0 ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
            return refund;
        });
    }

    public List<Refund> findByPayment(String paymentId) {
        return refunds.stream().filter(r -> r.paymentId().equals(paymentId)).toList();
    }
}
