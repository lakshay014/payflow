package com.payflow.exception;

/** Base runtime exception for business-rule violations. */
public class PayFlowException extends RuntimeException {
    public PayFlowException(String message) { super(message); }
}
