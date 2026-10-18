package rw.teleclinic.core.emrsync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.teleclinic.core.consent.ConsentService;
import rw.teleclinic.core.matching.IdentityMatcher;
import rw.teleclinic.core.patientindex.Patient;
import rw.teleclinic.core.patientindex.PatientDirectory;
import rw.teleclinic.core.shared.ApiException;
import rw.teleclinic.core.triage.TriageFinalised;

/** Outbound writes to the facility EMR: queued with the state change, pulled by the gateway, retried, dead-lettered. */
@Service
class SyncQueue {

    record Item(UUID id, String kind, UUID patientId, String reference, JsonNode payload, int attempts) {
    }

    private static final Logger log = LoggerFactory.getLogger(SyncQueue.class);

    private final JdbcClient jdbc;
    private final PatientDirectory patients;
    private final ConsentService consents;
    private final IdentityMatcher matcher;
    private final SyncLedger ledger;
    private final SyncProperties props;
    private final ObjectMapper json;

    SyncQueue(JdbcClient jdbc, PatientDirectory patients, ConsentService consents, IdentityMatcher matcher, SyncLedger ledger,
              SyncProperties props, ObjectMapper json) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.consents = consents;
        this.matcher = matcher;
        this.ledger = ledger;
        this.props = props;
        this.json = json;
        for (String o : new String[] {"CREATED", "ADOPTED", "EXISTED", "UPDATED", "FAILED", "DEAD"}) {
            Metrics.counter("teleclinic.sync.outbound", "outcome", o);
        }
    }

    /** The clinician's decision becomes an encounter in the EMR, if the patient's consent covers treatment. */
    @Transactional
    boolean enqueueEncounter(TriageFinalised e) {
        Patient p = patients.require(e.patientId());
        if (consents.activeCovering(p.id(), "TREAT").isEmpty()) {
            ledger.event("OUTBOUND", "Encounter", e.consultationId().toString(), null, "NOT_RELEASED", "no consent covering TREAT");
            return false;
        }
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("teleclinicId", p.id().toString());
        patient.put("givenName", p.givenName());
        patient.put("familyName", p.familyName());
        patient.put("gender", p.sex());
        patient.put("birthDate", p.birthDate().toString());
        // A child's record at a facility carries the guardian's number as the contact phone
        patient.put("phone", patients.contactPhone(p.id()).orElse(null));
        patient.put("nationalId", p.nationalId());
        patient.put("upi", p.upi());
        Map<String, Object> encounter = new LinkedHashMap<>();
        encounter.put("consultationId", e.consultationId().toString());
        encounter.put("datetime", e.decidedAt().toString());
        encounter.put("urgency", e.finalUrgency());
        encounter.put("decision", ("CONFIRM".equals(e.decision()) ? "Confirmed" : "Overridden") + " by " + e.reviewerName()
                + (e.reason() == null || e.reason().isBlank() ? "" : ": " + e.reason()));
        encounter.put("channel", e.channel());
        return insert("ENCOUNTER", p.id(), e.consultationId().toString(), Map.of("patient", patient, "encounter", encounter));
    }

    @Transactional
    boolean enqueueIdentifiers(String kind, UUID linkId, UUID patientId, String emrUuid, Map<String, String> identifiers) {
        return insert(kind, patientId, linkId.toString(), Map.of("emrPatientUuid", emrUuid, "identifiers", identifiers));
    }

    private boolean insert(String kind, UUID patientId, String reference, Map<String, Object> payload) {
        int n = jdbc.sql("""
                        INSERT INTO sync.outbound (id, kind, patient_id, reference, payload) VALUES (?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (kind, reference) DO NOTHING""")
                .params(UUID.randomUUID(), kind, patientId, reference, toJson(payload)).update();
        return n == 1;
    }

    /** Oldest first, only what is due: an item waiting out its backoff is not handed out again yet. */
    @Transactional(readOnly = true)
    List<Item> due(int limit) {
        return jdbc.sql("""
                        SELECT * FROM sync.outbound WHERE status = 'PENDING' AND next_attempt_at <= now()
                        ORDER BY created_at LIMIT ?""")
                .param(Math.min(Math.max(limit, 1), 100))
                .query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("patient_id", UUID.class),
                        rs.getString("reference"), readJson(rs.getString("payload")), rs.getInt("attempts"))).list();
    }

    /** The gateway wrote the item. The EMR record as it now reads becomes the ledger's version and hash. */
    @Transactional
    void done(UUID id, String outcome, JsonNode emrPatient, String emrEncounterUuid) {
        Item item = jdbc.sql("SELECT * FROM sync.outbound WHERE id = ? FOR UPDATE").param(id)
                .query((rs, n) -> new Item(id, rs.getString("kind"), rs.getObject("patient_id", UUID.class), rs.getString("reference"),
                        readJson(rs.getString("payload")), rs.getInt("attempts"))).optional()
                .orElseThrow(() -> ApiException.notFound("No outbound item " + id));
        jdbc.sql("UPDATE sync.outbound SET status = 'DONE', done_at = now(), outcome = ?, last_error = NULL WHERE id = ?")
                .params(outcome, id).update();
        String emrUuid = emrPatient == null ? null : emrPatient.path("id").asText();
        if ("REMOVE_IDENTIFIER".equals(item.kind())) {
            ledger.forget("Patient", item.patientId().toString());
        } else if (emrPatient != null) {
            ledger.record("Patient", item.patientId().toString(), emrUuid, emrPatient.path("meta").path("lastUpdated").asText(null),
                    PatientContent.hash(emrPatient), "OUTBOUND");
        }
        if ("ADOPTED".equals(outcome)) {
            // Conditional create found the record by UPI or national ID: that is an automatic link
            matcher.recordAdoption(item.patientId(), emrUuid, "conditional create: the EMR record already carried the same national identifier");
        }
        if (emrEncounterUuid != null) {
            ledger.record("Encounter", item.reference(), emrEncounterUuid, null, item.reference(), "OUTBOUND");
        }
        ledger.event("OUTBOUND", "ENCOUNTER".equals(item.kind()) ? "Encounter" : "Patient",
                "ENCOUNTER".equals(item.kind()) ? item.reference() : item.patientId().toString(), emrUuid, outcome, item.kind());
        Metrics.counter("teleclinic.sync.outbound", "outcome", outcome).increment();
        log.info("Sync {} {} -> {} ({})", item.kind(), item.reference(), emrUuid, outcome);
    }

    /** Exponential backoff with jitter: 5 s, 10 s, 20 s ... capped at 5 minutes; dead letter after the limit. */
    @Transactional
    void failed(UUID id, String error) {
        int attempts = jdbc.sql("UPDATE sync.outbound SET attempts = attempts + 1, last_error = ? WHERE id = ? RETURNING attempts")
                .params(error == null ? "unknown" : error.length() > 500 ? error.substring(0, 500) : error, id)
                .query(Integer.class).optional().orElseThrow(() -> ApiException.notFound("No outbound item " + id));
        if (attempts >= props.maxAttempts()) {
            jdbc.sql("UPDATE sync.outbound SET status = 'DEAD' WHERE id = ?").param(id).update();
            Metrics.counter("teleclinic.sync.outbound", "outcome", "DEAD").increment();
            ledger.event("OUTBOUND", "Item", id.toString(), null, "DEAD", error);
            return;
        }
        long base = Math.min(300, 5L << Math.min(attempts - 1, 10));
        long delayMs = (long) (base * 1000 * (1 + ThreadLocalRandom.current().nextDouble(0.3)));
        jdbc.sql("UPDATE sync.outbound SET next_attempt_at = now() + (? * interval '1 millisecond') WHERE id = ?")
                .params(delayMs, id).update();
        Metrics.counter("teleclinic.sync.outbound", "outcome", "FAILED").increment();
    }

    /** Dead letters go back in the queue, in their original order. */
    @Transactional
    int replay() {
        return jdbc.sql("UPDATE sync.outbound SET status = 'PENDING', attempts = 0, next_attempt_at = now() WHERE status = 'DEAD'").update();
    }

    private String toJson(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readJson(String v) {
        try {
            return json.readTree(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
