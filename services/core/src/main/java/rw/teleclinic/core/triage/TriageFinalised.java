package rw.teleclinic.core.triage;

import java.time.Instant;
import java.util.UUID;

/** A clinician confirmed or overrode the triage: the case is FINAL and its encounter can go to the facility EMR. */
public record TriageFinalised(UUID consultationId, UUID patientId, String channel, String finalUrgency, String decision,
                              String reason, String reviewerName, Instant decidedAt) {
}
