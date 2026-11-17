package com.payflow.repository;

import com.payflow.exception.OptimisticLockException;
import com.payflow.model.Versioned;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Thread-safe in-memory store standing in for a JPA repository + MySQL table.
 * Stores defensive copies and enforces optimistic locking on save, like JPA's @Version:
 * a save succeeds only if the entity's version matches the stored version.
 */
public class InMemoryRepository<T extends Versioned<T>> {
    private final Map<String, T> store = new ConcurrentHashMap<>();

    public Optional<T> findById(String id) {
        T t = store.get(id);
        return Optional.ofNullable(t == null ? null : t.copy());
    }

    public List<T> findAll(Predicate<T> filter) {
        List<T> out = new ArrayList<>();
        for (T t : store.values()) if (filter.test(t)) out.add(t.copy());
        return out;
    }

    /**
     * Insert that fails if the id already exists — the equivalent of a primary-key / unique-index
     * violation. Used to guarantee one payment per order under concurrency.
     */
    public T insert(T entity) {
        T fresh = entity.copy();
        fresh.setVersion(0);
        if (store.putIfAbsent(entity.getId(), fresh) != null) {
            throw new OptimisticLockException(entity.getClass().getSimpleName() + " " + entity.getId() + " already exists");
        }
        entity.setVersion(0);
        return fresh.copy();
    }

    /** Update with version check (also inserts if absent). Returns the saved copy (new version). */
    public T save(T entity) {
        T[] result = newArray();
        store.compute(entity.getId(), (id, current) -> {
            long expected = current == null ? 0 : current.getVersion();
            if (current != null && entity.getVersion() != expected) {
                throw new OptimisticLockException(entity.getClass().getSimpleName() + " " + id
                        + " was modified concurrently (expected v" + entity.getVersion() + ", found v" + expected + ")");
            }
            T next = entity.copy();
            next.setVersion(current == null ? 0 : expected + 1);
            result[0] = next;
            return next;
        });
        entity.setVersion(result[0].getVersion());
        return result[0].copy();
    }

    @SuppressWarnings("unchecked")
    private T[] newArray() { return (T[]) new Versioned[1]; }
}
