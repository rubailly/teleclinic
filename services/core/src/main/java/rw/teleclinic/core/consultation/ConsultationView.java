package rw.teleclinic.core.consultation;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the patient app and the clinician see of a consultation, including every state change. */
public record ConsultationView(
        UUID id,
        UUID patientId,
        String bookedBySubject,
        String channel,
        String state,
        String urgency,
        List<String> redFlags,
        String patientAdvice,
        String rulesetVersion,
        BigDecimal amountDue,
        String currency,
        String coverageScheme,
        Instant paymentDueBy,
        List<Transition> history) {

    public record Transition(String from, String to, String reason, Instant at) {
    }

    static ConsultationView from(Map<String, Object> c, List<Transition> history) {
        Timestamp due = (Timestamp) c.get("payment_due_by");
        return new ConsultationView(
                (UUID) c.get("id"),
                (UUID) c.get("patient_id"),
                (String) c.get("booked_by_subject"),
                (String) c.get("channel"),
                (String) c.get("state"),
                (String) c.get("urgency"),
                strings(c.get("red_flags")),
                (String) c.get("patient_advice"),
                (String) c.get("ruleset_version"),
                (BigDecimal) c.get("amount_due"),
                (String) c.get("currency"),
                (String) c.get("coverage_scheme"),
                due == null ? null : due.toInstant(),
                history);
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
