package com.payflow.model;

import java.time.Instant;

public record Refund(String id, String paymentId, String gatewayRefundId, long amount, Instant createdAt) {}
