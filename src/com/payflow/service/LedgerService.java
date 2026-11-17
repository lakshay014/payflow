package com.payflow.service;

import com.payflow.model.EntryType;
import com.payflow.model.LedgerEntry;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Double-entry ledger. Every money movement writes a DEBIT and a CREDIT of the same amount,
 * so total debits always equal total credits. Postings are keyed by a transaction reference
 * and a reference can only be posted once (exactly-once at the ledger level).
 */
public class LedgerService {
    public static final String GATEWAY_SETTLEMENT = "ASSET:razorpay_settlement";
    public static final String MERCHANT_REVENUE   = "INCOME:merchant_revenue";

    private final List<LedgerEntry> entries = new CopyOnWriteArrayList<>();
    private final Set<String> postedRefs = new HashSet<>();

    /** @return true if posted, false if this reference was already posted (duplicate ignored). */
    public synchronized boolean post(String transactionRef, String debitAccount, String creditAccount, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Ledger amount must be positive");
        if (!postedRefs.add(transactionRef)) return false;
        Instant now = Instant.now();
        entries.add(new LedgerEntry(UUID.randomUUID().toString(), transactionRef, debitAccount, EntryType.DEBIT, amount, now));
        entries.add(new LedgerEntry(UUID.randomUUID().toString(), transactionRef, creditAccount, EntryType.CREDIT, amount, now));
        return true;
    }

    public boolean hasPosting(String transactionRef) {
        synchronized (this) { return postedRefs.contains(transactionRef); }
    }

    /** Debit-normal balance: debits minus credits. Income accounts therefore show negative numbers. */
    public long balance(String account) {
        long b = 0;
        for (LedgerEntry e : entries)
            if (e.account().equals(account)) b += e.type() == EntryType.DEBIT ? e.amount() : -e.amount();
        return b;
    }

    public boolean isBalanced() {
        long debits = 0, credits = 0;
        for (LedgerEntry e : entries) {
            if (e.type() == EntryType.DEBIT) debits += e.amount(); else credits += e.amount();
        }
        return debits == credits;
    }

    public List<LedgerEntry> entries() { return List.copyOf(entries); }
}
