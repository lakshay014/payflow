package com.payflow.service;

import com.payflow.exception.PayFlowException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Exactly-once request handling: the first request with a given Idempotency-Key runs the operation;
 * retries with the same key (even concurrent ones) get the stored result instead of re-running it.
 * A key reused with a different request body is rejected. Failed operations release the key so the
 * client can retry. In production this is a DB table with a unique index on the key.
 */
public class IdempotencyService {
    private record Entry(String requestFingerprint, CompletableFuture<Object> result) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T execute(String scope, String key, String requestFingerprint, Supplier<T> operation) {
        if (key == null || key.isBlank()) throw new PayFlowException("Idempotency-Key header is required");
        String fullKey = scope + ":" + key;
        Entry mine = new Entry(requestFingerprint, new CompletableFuture<>());
        Entry existing = entries.putIfAbsent(fullKey, mine);

        if (existing != null) {
            if (!existing.requestFingerprint().equals(requestFingerprint)) {
                throw new PayFlowException("Idempotency-Key '" + key + "' was already used with a different request");
            }
            try {
                return (T) existing.result().get();          // waits if the first request is still running
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PayFlowException("Interrupted while waiting for idempotent result");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : new PayFlowException(e.getMessage());
            }
        }

        try {
            T value = operation.get();
            mine.result().complete(value);
            return value;
        } catch (RuntimeException e) {
            entries.remove(fullKey);                         // let the client retry with the same key
            mine.result().completeExceptionally(e);
            throw e;
        }
    }
}
