package com.payflow.service;

import com.payflow.gateway.PaymentGateway;
import com.payflow.model.Payment;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled job (like Spring's @Scheduled) that
 *  - asks the gateway for the real status of every PENDING payment and syncs it,
 *  - retries failed captures with exponential backoff up to MAX_ATTEMPTS, then marks FAILED,
 *  - checks every CAPTURED payment has a ledger posting.
 */
public class ReconciliationService {
    public static final int MAX_ATTEMPTS = 3;

    private final PaymentService paymentService;
    private final PaymentGateway gateway;
    private final LedgerService ledger;
    private final Duration baseBackoff;
    private ScheduledExecutorService scheduler;

    public ReconciliationService(PaymentService paymentService, PaymentGateway gateway,
                                 LedgerService ledger, Duration baseBackoff) {
        this.paymentService = paymentService;
        this.gateway = gateway;
        this.ledger = ledger;
        this.baseBackoff = baseBackoff;
    }

    public void start(Duration interval) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reconciliation");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() { if (scheduler != null) scheduler.shutdownNow(); }

    private void runSafely() {
        try { runOnce(); } catch (RuntimeException e) { System.out.println("  [recon] error: " + e.getMessage()); }
    }

    public synchronized void runOnce() {
        for (Payment p : paymentService.findPending()) {
            if (p.getGatewayPaymentId() == null) continue;           // customer hasn't paid yet
            String gwStatus = gateway.fetchPaymentStatus(p.getGatewayPaymentId());
            switch (gwStatus) {
                case "captured" -> {
                    paymentService.markCaptured(p.getId());
                    System.out.println("  [recon] " + p.getId() + " was captured at gateway -> marked CAPTURED");
                }
                case "failed" -> {
                    paymentService.markFailed(p.getId());
                    System.out.println("  [recon] " + p.getId() + " failed at gateway -> marked FAILED");
                }
                default -> retryOrGiveUp(p);
            }
        }
        for (Payment p : paymentService.findCaptured()) {
            if (!ledger.hasPosting("capture:" + p.getId())) {
                System.out.println("  [recon] MISMATCH: " + p.getId() + " captured but missing ledger entry; posting");
                paymentService.markCaptured(p.getId());
            }
        }
    }

    private void retryOrGiveUp(Payment p) {
        if (p.getAttempts() >= MAX_ATTEMPTS) {
            paymentService.markFailed(p.getId());
            System.out.println("  [recon] " + p.getId() + " gave up after " + p.getAttempts() + " attempts -> FAILED");
            return;
        }
        Duration wait = baseBackoff.multipliedBy(1L << Math.max(0, p.getAttempts() - 1));   // 1x, 2x, 4x ...
        if (Instant.now().isBefore(p.getUpdatedAt().plus(wait))) return;                     // not due yet
        System.out.println("  [recon] retrying capture for " + p.getId() + " (attempt " + (p.getAttempts() + 1) + ")");
        Payment after = paymentService.attemptCapture(p.getId());
        System.out.println("  [recon] -> " + after.getStatus());
    }
}
