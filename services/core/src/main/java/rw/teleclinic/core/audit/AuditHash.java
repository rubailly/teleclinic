package rw.teleclinic.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** SHA-256 over the previous hash, the sequence number and every field of the entry, in a fixed order. */
final class AuditHash {

    static final String GENESIS = "0".repeat(64);
    private static final char FIELD = '\u001f';
    private static final String NULL = "\u0000";

    private AuditHash() {
    }

    static String of(long seq, AuditEntry e, String previousHash) {
        String detail = new TreeMap<>(e.detail()).entrySet().stream()
                .map(d -> d.getKey() + "=" + d.getValue()).collect(Collectors.joining("\u001e"));
        String canonical = String.join(String.valueOf(FIELD), previousHash, Long.toString(seq), s(e.eventId()),
                Long.toString(e.recordedAt().toEpochMilli()), s(e.source()), s(e.actor()), s(e.actorRole()),
                s(e.actorName()), s(e.action()), s(e.resourceType()), s(e.resourceId()), s(e.patientId()),
                s(e.purpose()), s(e.consentBasis()), s(e.outcome()), s(e.reason()), s(e.correlationId()), detail);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e1) {
            throw new IllegalStateException(e1);
        }
    }

    private static String s(Object value) {
        return value == null ? NULL : Objects.toString(value);
    }
}
