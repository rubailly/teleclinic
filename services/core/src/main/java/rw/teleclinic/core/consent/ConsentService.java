package rw.teleclinic.core.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.teleclinic.core.patientindex.PatientDirectory;
import rw.teleclinic.core.patientindex.Relation;
import rw.teleclinic.core.shared.ApiException;

/**
 * Consent before any clinical data moves (Section 4.3, Phase 2). For a dependent, the guardian gives
 * consent and is recorded as the performer (FHIR Consent.performer = the guardian's RelatedPerson).
 */
@Service
public class ConsentService {

    public static final String POLICY_VERSION = "teleclinic-consent-2026.1";
    public static final List<String> DEFAULT_PURPOSES = List.of("TREAT", "PAYMENT", "AI_TRIAGE_SUPPORT");

    public record Consent(UUID id, UUID patientId, String performerType, String performerSubject,
                          String policyVersion, List<String> purposes, String status, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final PatientDirectory patients;

    ConsentService(JdbcClient jdbc, PatientDirectory patients) {
        this.jdbc = jdbc;
        this.patients = patients;
    }

    @Transactional
    public Consent give(String subject, UUID patientId, List<String> purposes) {
        Relation relation = patients.relation(subject, patientId);
        if (relation == Relation.NONE) {
            throw ApiException.forbidden("forbidden", "You may only give consent for yourself or a verified dependent");
        }
        Optional<Consent> existing = active(patientId, subject);
        if (existing.isPresent()) {
            return existing.get();
        }
        Consent consent = new Consent(UUID.randomUUID(), patientId, relation == Relation.SELF ? "SELF" : "GUARDIAN",
                subject, POLICY_VERSION, purposes, "ACTIVE", Instant.now());
        jdbc.sql("""
                        INSERT INTO consent.consent (id, patient_id, performer_type, performer_subject, policy_version,
                                                     purposes, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(consent.id(), patientId, consent.performerType(), subject, POLICY_VERSION,
                        purposes.toArray(String[]::new), "ACTIVE", java.sql.Timestamp.from(consent.createdAt()))
                .update();
        return consent;
    }

    @Transactional(readOnly = true)
    public Optional<Consent> active(UUID patientId, String performerSubject) {
        return jdbc.sql("""
                        SELECT * FROM consent.consent
                        WHERE patient_id = ? AND performer_subject = ? AND status = 'ACTIVE'
                        ORDER BY created_at DESC LIMIT 1""")
                .param(patientId).param(performerSubject)
                .query(ConsentService::consent)
                .optional();
    }

    private static Consent consent(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Consent(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("performer_type"),
                rs.getString("performer_subject"),
                rs.getString("policy_version"),
                List.of((String[]) rs.getArray("purposes").getArray()),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** Any active consent for this patient that covers the purpose, whoever gave it (the patient or a guardian). */
    public Optional<Consent> activeCovering(UUID patientId, String purpose) {
        return jdbc.sql("""
                        SELECT * FROM consent.consent
                        WHERE patient_id = ? AND status = 'ACTIVE' AND ? = ANY(purposes)
                        ORDER BY created_at DESC LIMIT 1""")
                .param(patientId).param(purpose)
                .query(ConsentService::consent)
                .optional();
    }

    /** Throws 403 unless the person has an active consent covering the purpose for this patient. */
    public Consent requireFor(UUID patientId, String performerSubject, String purpose) {
        Consent consent = active(patientId, performerSubject).orElseThrow(() -> ApiException.forbidden(
                "consent-required", "No active consent for this patient; give consent before booking"));
        if (!consent.purposes().contains(purpose)) {
            throw ApiException.forbidden("consent-required", "Consent does not cover purpose " + purpose);
        }
        return consent;
    }

    @Transactional
    public void withdraw(String subject, UUID patientId) {
        jdbc.sql("""
                        UPDATE consent.consent SET status = 'WITHDRAWN', withdrawn_at = now()
                        WHERE patient_id = ? AND performer_subject = ? AND status = 'ACTIVE'""")
                .param(patientId).param(subject).update();
    }
}
