package rw.teleclinic.core.triage;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the clinician sees: rules and model side by side, the merged urgency, and the review. */
public record TriageCaseView(
        UUID consultationId,
        UUID patientId,
        String channel,
        String status,
        String rulesUrgency,
        List<String> redFlags,
        String rulesetVersion,
        String modelUrgency,
        BigDecimal modelConfidence,
        List<String> contributingFactors,
        String modelVersion,
        String modelMode,
        String mergedUrgency,
        String finalUrgency,
        Instant queuedAt,
        Instant slaDueAt,
        String reviewedBy,
        String reviewDecision,
        String reviewReason,
        Instant reviewedAt,
        List<Transition> history) {

    public record Transition(String from, String to, String reason, String actor, Instant at) {
    }

    static TriageCaseView from(Map<String, Object> r, List<Transition> history) {
        return new TriageCaseView(
                (UUID) r.get("consultation_id"), (UUID) r.get("patient_id"), (String) r.get("channel"),
                (String) r.get("status"), (String) r.get("rules_urgency"), strings(r.get("red_flags")),
                (String) r.get("ruleset_version"), (String) r.get("model_urgency"),
                (BigDecimal) r.get("model_confidence"), strings(r.get("model_factors")),
                (String) r.get("model_version"), (String) r.get("model_mode"), (String) r.get("merged_urgency"),
                (String) r.get("final_urgency"), instant(r.get("queued_at")), instant(r.get("sla_due_at")),
                (String) r.get("reviewer_name"), (String) r.get("review_decision"), (String) r.get("review_reason"),
                instant(r.get("reviewed_at")), history);
    }

    private static Instant instant(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }

    private static List<String> strings(Object array) {
        if (array == null) return List.of();
        try {
            return List.of((String[]) ((Array) array).getArray());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
