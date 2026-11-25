package org.andre.construx.modules.messaging.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Meta's {@code X-Hub-Signature-256}: {@code sha256=} plus the hex HMAC-SHA256 of the raw body under the app secret. */
final class WebhookSignature {
    private static final String PREFIX = "sha256=";

    private WebhookSignature() {
    }

    /** Constant-time check; a missing secret or header never matches. */
    static boolean isValid(byte[] rawBody, String header, String appSecret) {
        if (appSecret == null || appSecret.isBlank() || header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring(PREFIX.length()).toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(rawBody, appSecret), given);
    }

    private static byte[] hmac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
