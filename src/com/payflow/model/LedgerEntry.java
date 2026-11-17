package com.payflow.model;

import java.time.Instant;

/** One side of a double-entry posting. Every transaction produces one DEBIT and one CREDIT of equal amount. */
public record LedgerEntry(String id, String transactionRef, String account, EntryType type,
                          long amount, Instant createdAt) {}
