package com.payflow.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Razorpay signs the raw webhook body with HMAC-SHA256 using the webhook secret (X-Razorpay-Signature). */
public class WebhookSignatureVerifier {
    private final byte[] secret;

    public WebhookSignatureVerifier(String secret) { this.secret = secret.getBytes(StandardCharsets.UTF_8); }

    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute HMAC", e);
        }
    }

    /** Constant-time comparison to avoid timing attacks. */
    public boolean verify(String payload, String signature) {
        if (signature == null) return false;
        return MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
