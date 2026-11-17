package com.payflow.gateway;

public record GatewayResult(boolean success, String reference, String status, String error) {
    public static GatewayResult ok(String ref, String status) { return new GatewayResult(true, ref, status, null); }
    public static GatewayResult fail(String error) { return new GatewayResult(false, null, "failed", error); }
}
