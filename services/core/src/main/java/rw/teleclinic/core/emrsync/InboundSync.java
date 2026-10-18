package rw.teleclinic.core.emrsync;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.teleclinic.core.audit.Actor;
import rw.teleclinic.core.audit.AuditTrail;
import rw.teleclinic.core.matching.IdentityMatcher;
import rw.teleclinic.core.patientindex.Patient;
import rw.teleclinic.core.patientindex.PatientDirectory;

/**
 * Changes the gateway found in an EMR, already in FHIR (the gateway maps eBuzima's documents). Known record with the same content hash: an echo of TeleClinic's own write,
 * dropped. Known record with new content: EMR-authoritative fields (contact phone) are applied and audited. Unknown
 * record: identity matching decides.
 */
@Service
class InboundSync {

    record Outcome(String outcome, UUID patientId, String detail) {
        boolean echo() {
            return "ECHO_DROPPED".equals(outcome);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(InboundSync.class);
    private static final Instant EPOCH = Instant.parse("2000-01-01T00:00:00Z");

    private final JdbcClient jdbc;
    private final SyncLedger ledger;
    private final PatientDirectory patients;
    private final IdentityMatcher matcher;
    private final AuditTrail audit;
    private final SyncProperties props;

    InboundSync(JdbcClient jdbc, SyncLedger ledger, PatientDirectory patients, IdentityMatcher matcher, AuditTrail audit,
                SyncProperties props) {
        this.props = props;
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.patients = patients;
        this.matcher = matcher;
        this.audit = audit;
        for (String o : new String[] {"ECHO_DROPPED", "APPLIED", "UNCHANGED_FIELDS", "LINKED", "AUTO_LINKED", "REVIEW", "BLOCKED", "NO_MATCH"}) {
            Metrics.counter("teleclinic.sync.inbound", "outcome", o);
        }
    }

    /** Where the next poll of that EMR starts: the last poll's start, minus an overlap (repeats are dropped by the hash). */
    @Transactional(readOnly = true)
    Instant since(String system) {
        props.target(system);
        return jdbc.sql("SELECT value FROM sync.cursor WHERE name = ?").param(system + "-patients").query(java.sql.Timestamp.class)
                .optional().map(t -> t.toInstant().minusSeconds(10)).orElse(EPOCH);
    }

    @Transactional
    void advance(String system, Instant polledAt) {
        props.target(system);
        jdbc.sql("""
                        INSERT INTO sync.cursor (name, value) VALUES (?, ?)
                        ON CONFLICT (name) DO UPDATE SET value = greatest(sync.cursor.value, excluded.value)""")
                .params(system + "-patients", java.sql.Timestamp.from(polledAt)).update();
    }

    @Transactional
    Outcome receive(String system, JsonNode patient) {
        props.target(system);
        String emrUuid = patient.path("id").asText();
        String hash = PatientContent.hash(patient);
        Optional<UUID> known = resolve(system, patient, emrUuid);
        Outcome result;
        if (known.isPresent()) {
            UUID patientId = known.get();
            Optional<SyncLedger.Entry> entry = ledger.byTeleclinicId(system, "Patient", patientId.toString());
            if (entry.isPresent() && entry.get().contentHash().equals(hash)) {
                result = new Outcome("ECHO_DROPPED", patientId, "same content hash as the last sync");
            } else {
                result = apply(system, patientId, emrUuid, patient);
                ledger.record(system, "Patient", patientId.toString(), emrUuid, patient.path("meta").path("lastUpdated").asText(null),
                        hash, "INBOUND");
            }
        } else {
            IdentityMatcher.Resolution r = matcher.consider(system, PatientContent.toEmrPatient(patient));
            result = new Outcome(r.outcome(), r.patientId().orElse(null), null);
        }
        ledger.event(system, "INBOUND", "Patient", result.patientId() == null ? null : result.patientId().toString(), emrUuid,
                result.outcome(), result.detail());
        Metrics.counter("teleclinic.sync.inbound", "outcome", result.outcome()).increment();
        return result;
    }

    /** Contact details are EMR-authoritative; demographics and identifiers stay TeleClinic's (Section 4.5.2 authority rules). */
    private Outcome apply(String system, UUID patientId, String emrUuid, JsonNode patient) {
        Patient current = patients.require(patientId);
        String phone = PatientContent.phone(patient);
        if (phone != null && !Objects.equals(phone, current.phone())) {
            patients.updatePhone(patientId, phone);
            Actor emr = new Actor(system, "system", props.target(system).name() + " (through the sync gateway)");
            audit.record(emr, "UPDATE", "Patient", patientId.toString(), patientId, "OPERATIONS", "emr-authoritative",
                    "SUCCESS", null, Map.of("field", "phone", "emrPatient", emrUuid));
            log.info("Phone of patient {} updated from {}", patientId, system);
            return new Outcome("APPLIED", patientId, "phone");
        }
        return new Outcome("UNCHANGED_FIELDS", patientId, "no EMR-authoritative field changed");
    }

    /** By the TeleClinic ID written on the EMR record, then the ledger, then an identity link. */
    private Optional<UUID> resolve(String system, JsonNode patient, String emrUuid) {
        String tc = PatientContent.identifier(patient, PatientContent.TELECLINIC_ID);
        if (tc != null) {
            try {
                UUID id = UUID.fromString(tc);
                if (patients.findById(id).isPresent()) {
                    return Optional.of(id);
                }
            } catch (IllegalArgumentException ignored) {
                // not one of ours
            }
        }
        return ledger.byEmrUuid(system, "Patient", emrUuid).map(e -> UUID.fromString(e.teleclinicId()))
                .or(() -> matcher.linkedPatient(system, emrUuid));
    }
}
