package rw.teleclinic.core.consultation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.teleclinic.core.consent.ConsentService;
import rw.teleclinic.core.consultation.ConsultationEvents.ConsultationExpired;
import rw.teleclinic.core.consultation.ConsultationEvents.ConsultationPaid;
import rw.teleclinic.core.patientindex.Patient;
import rw.teleclinic.core.patientindex.PatientDirectory;
import rw.teleclinic.core.patientindex.Relation;
import rw.teleclinic.core.payments.CoverageService;
import rw.teleclinic.core.payments.PaymentConfirmed;
import rw.teleclinic.core.payments.PaymentService;
import rw.teleclinic.core.shared.ApiException;
import rw.teleclinic.core.triage.RuleResult;
import rw.teleclinic.core.triage.RulesEngine;
import rw.teleclinic.core.triage.TriageIntake;
import rw.teleclinic.core.triage.TriageCases;

/**
 * The booking and payment saga, orchestrated in one place (ADR-005). No two-phase commit with IremboPay
 * (ADR-002): each step is a local transaction, and every failure has a compensating path.
 */
@Service
public class ConsultationService {

    public record BookingRequest(@NotNull UUID patientId, String channel, @NotEmpty List<String> symptoms,
                                 Integer durationDays, Boolean pregnant, TriageIntake.Vitals vitals) {
    }

    public record PaymentRequestResult(UUID consultationId, String state, String checkoutUrl, BigDecimal amount,
                                       String currency, boolean insured, String coverageScheme, String coverageNote,
                                       Instant payBy) {
    }

    private static final Logger log = LoggerFactory.getLogger(ConsultationService.class);

    private final JdbcClient jdbc;
    private final PatientDirectory patients;
    private final ConsentService consents;
    private final RulesEngine rules;
    private final PaymentService payments;
    private final CoverageService coverage;
    private final TriageCases triage;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final ConsultationProperties props;

    ConsultationService(JdbcClient jdbc, PatientDirectory patients, ConsentService consents, RulesEngine rules,
                        PaymentService payments, CoverageService coverage, TriageCases triage, ApplicationEventPublisher events,
                        ObjectMapper json, ConsultationProperties props) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.consents = consents;
        this.rules = rules;
        this.payments = payments;
        this.coverage = coverage;
        this.triage = triage;
        this.events = events;
        this.json = json;
        this.props = props;
    }

    /** Step 1: consent check, synchronous rules triage, consultation recorded as TRIAGED. */
    @Transactional
    public ConsultationView book(String subject, BookingRequest req, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<UUID> existing = jdbc.sql("SELECT id FROM consultation.consultation WHERE idempotency_key = ?")
                    .param(idempotencyKey).query(UUID.class).optional();
            if (existing.isPresent()) {
                return view(existing.get());
            }
        }
        Relation relation = patients.relation(subject, req.patientId());
        if (relation == Relation.NONE) {
            throw ApiException.forbidden("forbidden", "You may only book for yourself or a verified dependent");
        }
        ConsentService.Consent consent = consents.requireFor(req.patientId(), subject, "TREAT");
        Patient patient = patients.require(req.patientId());

        UUID id = UUID.randomUUID();
        String channel = req.channel() == null ? "WEB" : req.channel();
        TriageIntake intake = new TriageIntake(id.toString(), channel, patient.ageBand(LocalDate.now()), patient.sex(),
                req.pregnant(), req.symptoms(), req.durationDays(), req.vitals());
        RuleResult result = rules.evaluate(intake);

        jdbc.sql("""
                        INSERT INTO consultation.consultation (id, patient_id, booked_by_subject, channel, state, intake,
                                                               idempotency_key)
                        VALUES (?, ?, ?, ?, 'DRAFT', ?::jsonb, ?)""")
                .params(id, req.patientId(), subject, channel, toJson(intake), idempotencyKey).update();
        record(id, null, ConsultationState.DRAFT, "booked by " + relation.name().toLowerCase() + " on " + channel);

        jdbc.sql("""
                        UPDATE consultation.consultation
                        SET urgency = ?, red_flags = ?, patient_advice = ?, ruleset_version = ?
                        WHERE id = ?""")
                .params(result.urgency().name(), result.redFlags().toArray(String[]::new), result.patientAdvice(),
                        result.rulesetVersion(), id).update();
        move(id, ConsultationState.DRAFT, ConsultationState.TRIAGED,
                "rules " + result.rulesetVersion() + ": " + result.urgency() + " " + result.redFlags());

        // Rules are the safety floor; the model is asked asynchronously only if consent covers AI support
        triage.open(id, req.patientId(), intake, result, consent.purposes().contains("AI_TRIAGE_SUPPORT"));
        return view(id);
    }

    /** Step 2: coverage check, payment created at IremboPay, consultation PENDING_PAYMENT with a timer. */
    @Transactional
    public PaymentRequestResult requestPayment(String subject, UUID id) {
        Map<String, Object> c = lockForBooker(id, subject);
        ConsultationState state = ConsultationState.valueOf((String) c.get("state"));
        if (state == ConsultationState.PENDING_PAYMENT) {
            PaymentService.PaymentIntent intent = payments.findByConsultation(id).orElseThrow();
            return result(id, state, intent, c);
        }
        if (state != ConsultationState.TRIAGED) {
            throw ApiException.conflict("invalid-state", "Payment can only be requested for a triaged consultation, not " + state);
        }
        Patient patient = patients.require((UUID) c.get("patient_id"));
        CoverageService.Coverage cover = coverage.check(patient.nationalId(), patient.upi());
        BigDecimal amount = cover.covered() ? cover.copayment() : props.fee();

        // If IremboPay is down this throws: nothing is committed and the patient keeps the rules advice
        PaymentService.PaymentIntent intent = payments.start(id, amount, props.currency(),
                "TeleClinic consultation", props.publicBaseUrl() + "/payment-return?consultation=" + id);

        Instant payBy = Instant.now().plus(props.paymentWindow());
        jdbc.sql("""
                        UPDATE consultation.consultation
                        SET fee = ?, amount_due = ?, currency = ?, coverage_scheme = ?, payment_due_by = ?
                        WHERE id = ?""")
                .params(props.fee(), amount, props.currency(), cover.scheme(), Timestamp.from(payBy), id).update();
        move(id, ConsultationState.TRIAGED, ConsultationState.PENDING_PAYMENT,
                "payment " + intent.providerPaymentId() + " created for " + amount + " " + props.currency()
                        + (cover.covered() ? " (" + cover.scheme() + " co-payment)" : ""));
        return new PaymentRequestResult(id, ConsultationState.PENDING_PAYMENT.name(), intent.checkoutUrl(), amount,
                props.currency(), cover.covered(), cover.scheme(), cover.note(), payBy);
    }

    /**
     * Step 3: IremboPay confirmed. Runs inside the payment's transaction, so PAID, QUEUED and the outbox event
     * commit together with the payment update or not at all.
     */
    @EventListener
    public void on(PaymentConfirmed confirmed) {
        UUID id = confirmed.consultationId();
        Map<String, Object> c = jdbc.sql("SELECT * FROM consultation.consultation WHERE id = ? FOR UPDATE")
                .param(id).query().singleRow();
        ConsultationState state = ConsultationState.valueOf((String) c.get("state"));
        switch (state) {
            case PENDING_PAYMENT -> {
                move(id, state, ConsultationState.PAID, "confirmed by IremboPay (" + confirmed.confirmedBy() + ")");
                move(id, ConsultationState.PAID, ConsultationState.QUEUED, "entered the clinician queue as " + c.get("urgency"));
                triage.markQueued(id);
                events.publishEvent(new ConsultationPaid(id, (UUID) c.get("patient_id"), (String) c.get("channel"),
                        (String) c.get("urgency"), confirmed.amount().toPlainString(), confirmed.currency(), Instant.now()));
            }
            // Money arrived after the slot was released: compensate with a refund, never silently keep it
            case EXPIRED -> move(id, state, ConsultationState.REFUND_PENDING,
                    "payment confirmed after the payment window closed; refund requested");
            default -> log.info("Payment confirmation for {} ignored in state {}", id, state);
        }
    }

    /** Timer path: before expiring, ask IremboPay directly in case the webhook was lost. */
    @Transactional
    public void expireIfOverdue(UUID id) {
        Optional<Map<String, Object>> c = jdbc.sql("""
                        SELECT * FROM consultation.consultation
                        WHERE id = ? AND state = 'PENDING_PAYMENT' AND payment_due_by < now()
                        FOR UPDATE SKIP LOCKED""")
                .param(id).query().listOfRows().stream().findFirst();
        if (c.isEmpty()) {
            return;
        }
        if (payments.reconcile(id)) {
            return; // the listener above moved it to PAID and QUEUED in this transaction
        }
        move(id, ConsultationState.PENDING_PAYMENT, ConsultationState.EXPIRED,
                "no payment within " + props.paymentWindow().toMinutes() + " min; slot released");
        events.publishEvent(new ConsultationExpired(id, (UUID) c.get().get("patient_id"), Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<UUID> overdue(int limit) {
        return jdbc.sql("""
                        SELECT id FROM consultation.consultation
                        WHERE state = 'PENDING_PAYMENT' AND payment_due_by < now() ORDER BY payment_due_by LIMIT ?""")
                .param(limit).query(UUID.class).list();
    }

    @Transactional(readOnly = true)
    public ConsultationView viewFor(String subject, boolean staff, UUID id) {
        ConsultationView view = view(id);
        if (!staff && !view.bookedBySubject().equals(subject)) {
            throw ApiException.notFound("Consultation " + id + " not found");
        }
        return view;
    }

    @Transactional(readOnly = true)
    public List<ConsultationView> bookedBy(String subject) {
        return jdbc.sql("SELECT id FROM consultation.consultation WHERE booked_by_subject = ? ORDER BY created_at DESC LIMIT 20")
                .param(subject).query(UUID.class).list().stream().map(this::view).toList();
    }

    public ConsultationView view(UUID id) {
        Map<String, Object> c = jdbc.sql("SELECT * FROM consultation.consultation WHERE id = ?").param(id).query()
                .listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Consultation " + id + " not found"));
        List<ConsultationView.Transition> history = jdbc.sql("""
                        SELECT from_state, to_state, reason, at FROM consultation.state_transition
                        WHERE consultation_id = ? ORDER BY id""")
                .param(id)
                .query((rs, n) -> new ConsultationView.Transition(rs.getString("from_state"), rs.getString("to_state"),
                        rs.getString("reason"), rs.getTimestamp("at").toInstant()))
                .list();
        return ConsultationView.from(c, history);
    }

    private Map<String, Object> lockForBooker(UUID id, String subject) {
        Map<String, Object> c = jdbc.sql("SELECT * FROM consultation.consultation WHERE id = ? FOR UPDATE")
                .param(id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Consultation " + id + " not found"));
        if (!subject.equals(c.get("booked_by_subject"))) {
            throw ApiException.notFound("Consultation " + id + " not found");
        }
        return c;
    }

    private PaymentRequestResult result(UUID id, ConsultationState state, PaymentService.PaymentIntent intent,
                                        Map<String, Object> c) {
        Timestamp due = (Timestamp) c.get("payment_due_by");
        return new PaymentRequestResult(id, state.name(), intent.checkoutUrl(), intent.amount(), intent.currency(),
                c.get("coverage_scheme") != null, (String) c.get("coverage_scheme"), null,
                due == null ? null : due.toInstant());
    }

    /** Guarded state change: only moves from the expected state, and records why. */
    private void move(UUID id, ConsultationState from, ConsultationState to, String reason) {
        int updated = jdbc.sql("""
                        UPDATE consultation.consultation SET state = ?, updated_at = now(), version = version + 1
                        WHERE id = ? AND state = ?""")
                .params(to.name(), id, from.name()).update();
        if (updated != 1) {
            throw ApiException.conflict("invalid-state", "Consultation " + id + " is no longer " + from);
        }
        record(id, from, to, reason);
    }

    private void record(UUID id, ConsultationState from, ConsultationState to, String reason) {
        jdbc.sql("INSERT INTO consultation.state_transition (consultation_id, from_state, to_state, reason) VALUES (?, ?, ?, ?)")
                .params(id, from == null ? null : from.name(), to.name(), reason).update();
        log.info("Consultation {} {} -> {}: {}", id, from, to, reason);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
