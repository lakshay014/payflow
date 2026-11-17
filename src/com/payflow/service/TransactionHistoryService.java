package com.payflow.service;

import com.payflow.model.*;
import com.payflow.repository.InMemoryRepository;

import java.util.ArrayList;
import java.util.List;

/** GET /api/customers/{id}/transactions */
public class TransactionHistoryService {
    private final InMemoryRepository<Order> orders;
    private final PaymentService paymentService;
    private final RefundService refundService;

    public TransactionHistoryService(InMemoryRepository<Order> orders, PaymentService paymentService, RefundService refundService) {
        this.orders = orders;
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    public List<Transaction> forCustomer(String customerId) {
        List<Transaction> out = new ArrayList<>();
        for (Order o : orders.findAll(o -> o.getCustomerId().equals(customerId))) {
            paymentService.findByOrder(o.getId()).ifPresent(p -> {
                out.add(new Transaction("PAYMENT", p.getId(), o.getId(), p.getAmount(), p.getStatus().name(), p.getCreatedAt()));
                for (Refund r : refundService.findByPayment(p.getId()))
                    out.add(new Transaction("REFUND", r.id(), o.getId(), r.amount(), "PROCESSED", r.createdAt()));
            });
        }
        out.sort(PaymentService.byTime());
        return out;
    }
}
