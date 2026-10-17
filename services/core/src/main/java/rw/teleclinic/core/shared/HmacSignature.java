package rw.teleclinic.core.shared;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature header {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">}, used for IremboPay webhooks and
 * for callbacks from the AI trust zone. The timestamp window stops replays of old, captured messages.
 */
public final class HmacSignature {

    private final byte[] secret;
    private final Duration tolerance;

    public HmacSignature(String secret, Duration tolerance) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
    }

    public boolean isValid(String header, String body, Instant now) {
        if (header == null || body == null) {
            return false;
        }
        Long ts = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) {
                try {
                    ts = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (ts == null || v1 == null) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(ts), now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = hmac(ts + "." + body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.US_ASCII));
    }

    public String sign(String body, Instant at) {
        long ts = at.getEpochSecond();
        return "t=" + ts + ",v1=" + hmac(ts + "." + body);
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
