package com.payflow.model;

/** Entities with an id and a version column for optimistic locking (like JPA @Version). */
public interface Versioned<T> {
    String getId();
    long getVersion();
    void setVersion(long version);
    T copy();
}
