package rw.teleclinic.core.triage;

import io.micrometer.core.instrument.Metrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.teleclinic.core.shared.ApiException;

/**
 * Human in the loop, enforced in the domain model rather than the screen (Section 4.7.3):
 * the model can only raise urgency, its result stays AI_PROPOSED, and nothing clinical can happen until a
 * clinician confirms or overrides it.
 */
@Service
public class TriageCases {

    public enum SuggestionOutcome { PROPOSED, UPDATED_DURING_REVIEW, SHADOW, ALREADY_FINAL, UNKNOWN_CASE }

    private static final Logger log = LoggerFactory.getLogger(TriageCases.class);

    /** Illustrative review deadlines by urgency; real values come from the clinical protocol. */
    private static final Map<Urgency, Duration> SLA = Map.of(
            Urgency.EMERGENCY, Duration.ofMinutes(15), Urgency.URGENT, Duration.ofMinutes(60),
            Urgency.SEMI_URGENT, Duration.ofHours(4), Urgency.ROUTINE, Duration.ofHours(24));

    private static final String URGENCY_ORDER = """
            CASE merged_urgency WHEN 'EMERGENCY' THEN 4 WHEN 'URGENT' THEN 3 WHEN 'SEMI_URGENT' THEN 2 ELSE 1 END""";

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final ModelControl models;
    private final ObjectMapper json;
    private final TriageProperties props;

    TriageCases(JdbcClient jdbc, ApplicationEventPublisher events, ModelControl models, ObjectMapper json,
                TriageProperties props) {
        for (TriageCaseStatus s : TriageCaseStatus.values()) {
            Metrics.counter("teleclinic.triage.transitions", "to", s.name());
        }
        this.jdbc = jdbc;
        this.events = events;
        this.models = models;
        this.json = json;
        this.props = props;
    }

    /**
     * Opens the case with the rules result as the safety floor. If consent covers AI support and the model is
     * not switched off, minimised features go to the model asynchronously (triage.requested).
     */
    @Transactional
    public void open(UUID consultationId, UUID patientId, TriageIntake intake, RuleResult rules, boolean aiAllowed) {
        jdbc.sql("""
                        INSERT INTO triage.triage_case (consultation_id, patient_id, channel, status, rules_urgency,
                                                        red_flags, ruleset_version, merged_urgency)
                        VALUES (?, ?, ?, 'RULES_ONLY', ?, ?, ?, ?)""")
                .params(consultationId, patientId, intake.channel(), rules.urgency().name(),
                        rules.redFlags().toArray(String[]::new), rules.rulesetVersion(), rules.urgency().name())
                .update();
        record(consultationId, null, TriageCaseStatus.RULES_ONLY,
                "rules " + rules.rulesetVersion() + ": " + rules.urgency(), "rules:" + rules.rulesetVersion());
        if (!aiAllowed) {
            log.info("Case {} stays rules-only: consent does not cover AI support", consultationId);
        } else if (models.isDisabled()) {
            log.info("Case {} stays rules-only: model switched off", consultationId);
        } else {
            events.publishEvent(new TriageRequested(consultationId, intake.channel(), intake.ageBand(), intake.sex(),
                    intake.isPregnant(), intake.symptoms(), intake.durationDays(), intake.vitals(), rules.urgency(),
                    rules.rulesetVersion()));
        }
    }

    /** The consultation is paid and enters the clinician queue; the review deadline starts now. */
    @Transactional
    public void markQueued(UUID consultationId) {
        Map<String, Object> c = lock(consultationId);
        Instant now = Instant.now();
        jdbc.sql("UPDATE triage.triage_case SET queued_at = ?, sla_due_at = ?, updated_at = now() WHERE consultation_id = ?")
                .params(Timestamp.from(now), Timestamp.from(now.plus(sla((String) c.get("merged_urgency")))), consultationId)
                .update();
    }

    @Transactional(readOnly = true)
    public boolean isFinal(UUID consultationId) {
        return jdbc.sql("SELECT status FROM triage.triage_case WHERE consultation_id = ?").param(consultationId)
                .query(String.class).optional().map("FINAL"::equals).orElse(false);
    }

    /** A model suggestion arrived from the AI trust zone. Merged with the rules floor: the model can only escalate. */
    @Transactional
    public SuggestionOutcome applySuggestion(UUID consultationId, Urgency model, BigDecimal confidence,
                                             List<String> factors, String modelVersion, String mode) {
        Map<String, Object> c = jdbc.sql("SELECT * FROM triage.triage_case WHERE consultation_id = ? FOR UPDATE")
                .param(consultationId).query().listOfRows().stream().findFirst().orElse(null);
        if (c == null) {
            return SuggestionOutcome.UNKNOWN_CASE;
        }
        TriageCaseStatus status = TriageCaseStatus.valueOf((String) c.get("status"));
        Urgency rules = Urgency.valueOf((String) c.get("rules_urgency"));
        Urgency merged = Urgency.max(rules, model);
        provenance(consultationId, "AI_SUGGESTION", "DEVICE", modelVersion, Map.of(
                "urgency", model.name(), "confidence", confidence, "factors", factors, "mode", mode,
                "rulesFloor", rules.name(), "merged", merged.name()));
        if (status == TriageCaseStatus.FINAL || status == TriageCaseStatus.CONFIRMED || status == TriageCaseStatus.OVERRIDDEN) {
            return SuggestionOutcome.ALREADY_FINAL;
        }
        if ("SHADOW".equals(mode)) {
            // Shadow mode: recorded for evaluation, never shown and never changes the case
            return SuggestionOutcome.SHADOW;
        }
        jdbc.sql("""
                        UPDATE triage.triage_case SET model_urgency = ?, model_confidence = ?, model_factors = ?,
                               model_version = ?, model_mode = ?, merged_urgency = ?, updated_at = now()
                        WHERE consultation_id = ?""")
                .params(model.name(), confidence, factors.toArray(String[]::new), modelVersion, mode, merged.name(),
                        consultationId).update();
        if (c.get("queued_at") != null && merged.compareTo(Urgency.valueOf((String) c.get("merged_urgency"))) > 0) {
            Instant queued = ((Timestamp) c.get("queued_at")).toInstant();
            jdbc.sql("UPDATE triage.triage_case SET sla_due_at = ? WHERE consultation_id = ?")
                    .params(Timestamp.from(queued.plus(sla(merged.name()))), consultationId).update();
        }
        if (status == TriageCaseStatus.RULES_ONLY) {
            move(consultationId, status, TriageCaseStatus.AI_PROPOSED,
                    "model " + modelVersion + ": " + model + " (" + confidence + "); merged " + merged, "model:" + modelVersion);
            return SuggestionOutcome.PROPOSED;
        }
        return SuggestionOutcome.UPDATED_DURING_REVIEW;
    }

    @Transactional
    public TriageCaseView openForReview(UUID consultationId, String reviewerName) {
        Map<String, Object> c = lock(consultationId);
        TriageCaseStatus status = TriageCaseStatus.valueOf((String) c.get("status"));
        if (status == TriageCaseStatus.UNDER_REVIEW) {
            return view(consultationId);
        }
        if (!status.reviewable()) {
            throw ApiException.conflict("invalid-state", "Case is " + status + " and cannot be opened for review");
        }
        move(consultationId, status, TriageCaseStatus.UNDER_REVIEW, "opened by " + reviewerName, "clinician:" + reviewerName);
        return view(consultationId);
    }

    /** The signed clinical decision. An override needs a reason; a confirmation keeps the merged urgency. */
    @Transactional
    public TriageCaseView review(UUID consultationId, String decision, Urgency finalUrgency, String reason,
                                 String reviewerSubject, String reviewerName) {
        Map<String, Object> c = lock(consultationId);
        TriageCaseStatus status = TriageCaseStatus.valueOf((String) c.get("status"));
        if (status == TriageCaseStatus.FINAL) {
            throw ApiException.conflict("already-final", "Case is already finalised");
        }
        if (!status.reviewable()) {
            throw ApiException.conflict("invalid-state", "Case is " + status);
        }
        if (status != TriageCaseStatus.UNDER_REVIEW) {
            move(consultationId, status, TriageCaseStatus.UNDER_REVIEW, "opened by " + reviewerName, "clinician:" + reviewerName);
        }
        Urgency merged = Urgency.valueOf((String) c.get("merged_urgency"));
        TriageCaseStatus decided;
        Urgency chosen;
        if ("CONFIRM".equals(decision)) {
            if (finalUrgency != null && finalUrgency != merged) {
                throw ApiException.unprocessable("invalid", "A confirmation keeps urgency " + merged + "; use OVERRIDE to change it");
            }
            decided = TriageCaseStatus.CONFIRMED;
            chosen = merged;
        } else if ("OVERRIDE".equals(decision)) {
            if (finalUrgency == null || reason == null || reason.isBlank()) {
                throw ApiException.unprocessable("invalid", "An override needs a final urgency and a reason");
            }
            decided = TriageCaseStatus.OVERRIDDEN;
            chosen = finalUrgency;
        } else {
            throw ApiException.unprocessable("invalid", "decision must be CONFIRM or OVERRIDE");
        }
        jdbc.sql("""
                        UPDATE triage.triage_case SET final_urgency = ?, reviewer_subject = ?, reviewer_name = ?,
                               review_decision = ?, review_reason = ?, reviewed_at = now(), updated_at = now()
                        WHERE consultation_id = ?""")
                .params(chosen.name(), reviewerSubject, reviewerName, decision, reason, consultationId).update();
        move(consultationId, TriageCaseStatus.UNDER_REVIEW, decided,
                decision.toLowerCase() + " to " + chosen + (reason == null || reason.isBlank() ? "" : ": " + reason),
                "clinician:" + reviewerName);
        move(consultationId, decided, TriageCaseStatus.FINAL, "orders, referrals and prescriptions unlocked",
                "clinician:" + reviewerName);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("decision", decision);
        detail.put("finalUrgency", chosen.name());
        detail.put("reason", reason);
        detail.put("rulesUrgency", c.get("rules_urgency"));
        detail.put("modelUrgency", c.get("model_urgency"));
        detail.put("modelVersion", c.get("model_version"));
        provenance(consultationId, "CLINICIAN_REVIEW", "PRACTITIONER", reviewerSubject, detail);
        TriageCaseView finalView = view(consultationId);
        events.publishEvent(new TriageFinalised(consultationId, finalView.patientId(), finalView.channel(), chosen.name(),
                decision, reason, reviewerName, Instant.now()));
        return finalView;
    }

    /** Paid cases waiting for a clinician, most urgent first, then oldest first. */
    @Transactional(readOnly = true)
    public List<TriageCaseView> queue() {
        return jdbc.sql("SELECT consultation_id FROM triage.triage_case WHERE queued_at IS NOT NULL AND status <> 'FINAL' "
                        + "ORDER BY " + URGENCY_ORDER + " DESC, queued_at")
                .query(UUID.class).list().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> provenance(UUID consultationId) {
        return jdbc.sql("SELECT activity, agent_type, agent_id, detail::text AS detail, recorded_at FROM triage.provenance "
                        + "WHERE consultation_id = ? ORDER BY id")
                .param(consultationId).query().listOfRows();
    }

    @Transactional(readOnly = true)
    public TriageCaseView view(UUID consultationId) {
        Map<String, Object> c = jdbc.sql("SELECT * FROM triage.triage_case WHERE consultation_id = ?").param(consultationId)
                .query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Triage case " + consultationId + " not found"));
        List<TriageCaseView.Transition> history = jdbc.sql("""
                        SELECT from_status, to_status, reason, actor, at FROM triage.case_transition
                        WHERE consultation_id = ? ORDER BY id""")
                .param(consultationId)
                .query((rs, n) -> new TriageCaseView.Transition(rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("reason"), rs.getString("actor"), rs.getTimestamp("at").toInstant()))
                .list();
        return TriageCaseView.from(c, history);
    }

    /** Unreviewed past the deadline: escalate to a supervisor. The rules urgency stays in force. */
    @Scheduled(fixedDelayString = "${teleclinic.triage.timeout-interval:PT20S}", initialDelayString = "${teleclinic.consultation.timer-initial-delay:PT10S}")
    @Transactional
    public void escalateOverdue() {
        List<UUID> overdue = jdbc.sql("""
                        SELECT consultation_id FROM triage.triage_case
                        WHERE status IN ('RULES_ONLY', 'AI_PROPOSED') AND sla_due_at < now()
                        FOR UPDATE SKIP LOCKED""")
                .query(UUID.class).list();
        for (UUID id : overdue) {
            String status = jdbc.sql("SELECT status FROM triage.triage_case WHERE consultation_id = ?").param(id)
                    .query(String.class).single();
            move(id, TriageCaseStatus.valueOf(status), TriageCaseStatus.UNREVIEWED_TIMEOUT,
                    "not reviewed within the deadline; escalated to the supervisor", "timer");
        }
    }

    private Duration sla(String urgency) {
        Duration base = SLA.get(Urgency.valueOf(urgency));
        return Duration.ofMillis((long) (base.toMillis() * props.slaScale()));
    }

    private Map<String, Object> lock(UUID consultationId) {
        return jdbc.sql("SELECT * FROM triage.triage_case WHERE consultation_id = ? FOR UPDATE").param(consultationId)
                .query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Triage case " + consultationId + " not found"));
    }

    private void move(UUID id, TriageCaseStatus from, TriageCaseStatus to, String reason, String actor) {
        int updated = jdbc.sql("UPDATE triage.triage_case SET status = ?, updated_at = now() WHERE consultation_id = ? AND status = ?")
                .params(to.name(), id, from.name()).update();
        if (updated != 1) {
            throw ApiException.conflict("invalid-state", "Triage case " + id + " is no longer " + from);
        }
        record(id, from, to, reason, actor);
    }

    private void record(UUID id, TriageCaseStatus from, TriageCaseStatus to, String reason, String actor) {
        jdbc.sql("INSERT INTO triage.case_transition (consultation_id, from_status, to_status, reason, actor) VALUES (?, ?, ?, ?, ?)")
                .params(id, from == null ? null : from.name(), to.name(), reason, actor).update();
        log.info("Triage {} {} -> {}: {}", id, from, to, reason);
        Metrics.counter("teleclinic.triage.transitions", "to", to.name()).increment();
    }

    private void provenance(UUID id, String activity, String agentType, String agentId, Map<String, Object> detail) {
        try {
            jdbc.sql("INSERT INTO triage.provenance (consultation_id, activity, agent_type, agent_id, detail) VALUES (?, ?, ?, ?, ?::jsonb)")
                    .params(id, activity, agentType, agentId, json.writeValueAsString(detail)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
