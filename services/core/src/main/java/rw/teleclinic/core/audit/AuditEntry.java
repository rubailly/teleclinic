package rw.teleclinic.core.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * One audit record before it is chained. Published through the outbox to audit.events, so an access is never
 * lost when Kafka is down; the chain writer gives it a sequence number and a hash.
 */
@Externalized("audit.events::#{#this.eventId()}")
public record AuditEntry(
        UUID eventId,
        Instant recordedAt,
        String source,
        String actor,
        String actorRole,
        String actorName,
        String action,
        String resourceType,
        String resourceId,
        UUID patientId,
        String purpose,
        String consentBasis,
        String outcome,
        String reason,
        String correlationId,
        Map<String, String> detail) {

    public static final String APPLICATION = "APPLICATION";
    public static final String DATABASE = "DATABASE";
    public static final String SUCCESS = "SUCCESS";
    public static final String DENIED = "DENIED";

    public AuditEntry {
        // Millisecond precision, so the value hashed at write time is exactly the value read back to verify
        recordedAt = Instant.ofEpochMilli(recordedAt.toEpochMilli());
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
