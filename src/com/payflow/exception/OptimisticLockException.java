package com.payflow.exception;

/** Thrown when an entity was modified by someone else since it was read. */
public class OptimisticLockException extends PayFlowException {
    public OptimisticLockException(String message) { super(message); }
}
