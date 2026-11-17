package com.payflow.model;

import java.time.Instant;

/** Read model for transaction history. */
public record Transaction(String type, String referenceId, String orderId, long amount,
                          String status, Instant at) {}
