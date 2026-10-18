package rw.teleclinic.core.matching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.teleclinic.core.audit.Actor;
import rw.teleclinic.core.audit.AuditTrail;
import rw.teleclinic.core.matching.IdentityEvents.PatientLinked;
import rw.teleclinic.core.matching.IdentityEvents.PatientUnlinked;
import rw.teleclinic.core.matching.MatchScore.Band;
import rw.teleclinic.core.patientindex.Patient;
import rw.teleclinic.core.patientindex.PatientDirectory;
import rw.teleclinic.core.shared.ApiException;

/** Decides what an unknown EMR record is to TeleClinic, and keeps the reversible links and the review queue. */
@Service
public class IdentityMatcher {

    /** What the sync gateway learns about an EMR record: which TeleClinic patient it is, if any, and how that was decided. */
    public record Resolution(Optional<UUID> patientId, String outcome) {
    }

    record Candidate(UUID id, String system, String emrUuid, UUID patientId, EmrPatient emr, double score, String band,
                     List<String> agreeing, List<String> conflicting, String status, String decidedBy, String reason) {
    }

    record Link(UUID id, UUID patientId, String system, String emrUuid, double score, String decidedBy, String reason,
                java.time.Instant decidedAt, Map<String, String> writtenIdentifiers) {
    }

    static final Actor MATCHER = new Actor("identity-matcher", "system", "Identity matcher");
    private static final Logger log = LoggerFactory.getLogger(IdentityMatcher.class);

    private final JdbcClient jdbc;
    private final PatientDirectory patients;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    IdentityMatcher(JdbcClient jdbc, PatientDirectory patients, AuditTrail audit, ApplicationEventPublisher events,
                    ObjectMapper json) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.audit = audit;
        this.events = events;
        this.json = json;
        for (String outcome : new String[] {"LINKED", "AUTO_LINKED", "REVIEW", "BLOCKED", "NO_MATCH"}) {
            Metrics.counter("teleclinic.identity.considered", "outcome", outcome);
        }
    }

    public Optional<UUID> linkedPatient(String system, String emrUuid) {
        return jdbc.sql("SELECT patient_id FROM matching.link WHERE system = ? AND emr_uuid = ? AND active").params(system, emrUuid)
                .query(UUID.class).optional();
    }

    /** Blocking, scoring and the three bands for one record of that EMR that TeleClinic does not know yet. */
    @Transactional
    public Resolution consider(String system, EmrPatient emr) {
        Optional<UUID> linked = linkedPatient(system, emr.emrUuid());
        if (linked.isPresent()) {
            return count(new Resolution(linked, "LINKED"));
        }
        List<Map.Entry<Patient, MatchScore>> pairs = patients.all().stream()
                .filter(p -> MatchScore.block(p, emr))
                .map(p -> Map.entry(p, MatchScore.of(p, emr)))
                .toList();
        pairs.stream().filter(e -> e.getValue().band() == Band.BLOCKED)
                .forEach(e -> upsertCandidate(system, e.getKey(), emr, e.getValue(), "BLOCKED"));
        Optional<Map.Entry<Patient, MatchScore>> best = pairs.stream()
                .filter(e -> e.getValue().band() != Band.BLOCKED)
                .max(Comparator.comparingDouble(e -> e.getValue().score()));
        if (best.isPresent() && best.get().getValue().band() == Band.AUTO) {
            Patient p = best.get().getKey();
            MatchScore s = best.get().getValue();
            UUID candidate = upsertCandidate(system, p, emr, s, "LINKED");
            createLink(candidate, system, p, emr, s.score(), MATCHER, "auto",
                    s.strongAgreements() + " strong identifiers agree: " + String.join(", ", s.agreeing()));
            return count(new Resolution(Optional.of(p.id()), "AUTO_LINKED"));
        }
        if (best.isPresent() && best.get().getValue().band() == Band.REVIEW) {
            upsertCandidate(system, best.get().getKey(), emr, best.get().getValue(), "PENDING");
            return count(new Resolution(Optional.empty(), "REVIEW"));
        }
        boolean blocked = pairs.stream().anyMatch(e -> e.getValue().band() == Band.BLOCKED);
        return count(new Resolution(Optional.empty(), blocked ? "BLOCKED" : "NO_MATCH"));
    }

    /** The gateway's conditional create found the EMR record by a national identifier: record it as an automatic link. */
    @Transactional
    public void recordAdoption(UUID patientId, String system, String emrUuid, String how) {
        if (linkedPatient(system, emrUuid).isPresent()) {
            return;
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO matching.link (id, patient_id, system, emr_uuid, score, decided_by, reason, written_identifiers)
                        VALUES (?, ?, ?, ?, NULL, 'auto', ?, '{}'::jsonb)""")
                .params(id, patientId, system, emrUuid, how).update();
        audit.record(MATCHER, "LINK", "Patient", patientId.toString(), patientId, "OPERATIONS", "identity-matching",
                "SUCCESS", how, Map.of("emr", system, "emrPatient", emrUuid, "linkId", id.toString()));
    }

    @Transactional
    public Link link(UUID patientId, String emrUuid, Actor clerk, String reason) {
        Candidate c = candidates("c.patient_id = ? AND c.emr_uuid = ? AND c.status = 'PENDING'", patientId, emrUuid).stream()
                .findFirst().orElseThrow(() -> ApiException.conflict("not-in-queue",
                        "No pending review for Patient/" + patientId + " and EMR record " + emrUuid));
        if (linkedPatient(c.system(), emrUuid).isPresent()) {
            throw ApiException.conflict("already-linked", "EMR record " + emrUuid + " is already linked");
        }
        Patient p = patients.require(patientId);
        jdbc.sql("UPDATE matching.candidate SET status = 'LINKED', decided_by = ?, decided_at = now(), reason = ? WHERE id = ?")
                .params(clerk.subject(), reason, c.id()).update();
        return createLink(c.id(), c.system(), p, c.emr(), c.score(), clerk, clerk.subject(), reason);
    }

    @Transactional
    public void dismiss(UUID candidateId, Actor clerk, String reason) {
        Candidate c = candidates("c.id = ? AND c.status = 'PENDING'", candidateId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("No pending review " + candidateId));
        jdbc.sql("UPDATE matching.candidate SET status = 'DISMISSED', decided_by = ?, decided_at = now(), reason = ? WHERE id = ?")
                .params(clerk.subject(), reason, candidateId).update();
        audit.record(clerk, "DISMISS", "Patient", c.patientId().toString(), c.patientId(), "OPERATIONS", "identity-review",
                "SUCCESS", reason, Map.of("emrPatient", c.emrUuid(), "score", Double.toString(c.score())));
    }

    @Transactional
    public void unlink(UUID linkId, Actor clerk, String reason) {
        Link l = links("id = ? AND active", linkId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("No active link " + linkId));
        jdbc.sql("UPDATE matching.link SET active = false, unlinked_by = ?, unlinked_at = now(), unlink_reason = ? WHERE id = ?")
                .params(clerk.subject(), reason, linkId).update();
        // Back in the queue as undecided, so a corrected decision can be made later
        jdbc.sql("UPDATE matching.candidate SET status = 'PENDING', decided_by = NULL, decided_at = NULL, reason = NULL "
                        + "WHERE system = ? AND patient_id = ? AND emr_uuid = ? AND status = 'LINKED'")
                .params(l.system(), l.patientId(), l.emrUuid()).update();
        audit.record(clerk, "UNLINK", "Patient", l.patientId().toString(), l.patientId(), "OPERATIONS", "identity-review",
                "SUCCESS", reason, Map.of("emr", l.system(), "emrPatient", l.emrUuid(), "linkId", linkId.toString()));
        events.publishEvent(new PatientUnlinked(linkId, l.patientId(), l.system(), l.emrUuid(), l.writtenIdentifiers()));
    }

    List<Candidate> queue() {
        return candidates("c.status = 'PENDING' ORDER BY c.score DESC");
    }

    List<Candidate> blocked() {
        return candidates("c.status = 'BLOCKED' ORDER BY c.created_at DESC");
    }

    List<Link> activeLinks() {
        return links("active ORDER BY decided_at DESC");
    }

    private Link createLink(UUID candidateId, String system, Patient p, EmrPatient emr, double score, Actor by, String decidedBy, String reason) {
        // What the EMR record should learn: TeleClinic's id, and national identifiers it is missing
        Map<String, String> write = new LinkedHashMap<>();
        write.put("teleclinicId", p.id().toString());
        if (p.nationalId() != null && emr.nationalId() == null) {
            write.put("nationalId", p.nationalId());
        }
        if (p.upi() != null && emr.upi() == null) {
            write.put("upi", p.upi());
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO matching.link (id, patient_id, system, emr_uuid, score, decided_by, reason, written_identifiers, candidate_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""")
                .params(id, p.id(), system, emr.emrUuid(), score, decidedBy, reason, toJson(write), candidateId).update();
        audit.record(by, "LINK", "Patient", p.id().toString(), p.id(), "OPERATIONS", "identity-review", "SUCCESS", reason,
                Map.of("emr", system, "emrPatient", emr.emrUuid(), "score", Double.toString(score), "linkId", id.toString()));
        events.publishEvent(new PatientLinked(id, p.id(), system, emr.emrUuid(), write));
        log.info("Linked TeleClinic patient {} to {} record {} ({}, score {})", p.id(), system, emr.emrUuid(), decidedBy, score);
        return links("id = ?", id).get(0);
    }

    private UUID upsertCandidate(String system, Patient p, EmrPatient emr, MatchScore s, String status) {
        Optional<UUID> existing = jdbc.sql("SELECT id FROM matching.candidate WHERE system = ? AND emr_uuid = ? AND patient_id = ?")
                .params(system, emr.emrUuid(), p.id()).query(UUID.class).optional();
        if (existing.isPresent()) {
            // A decided pair keeps its decision; an undecided one gets the latest evidence
            jdbc.sql("""
                            UPDATE matching.candidate SET emr_record = ?::jsonb, score = ?, band = ?, agreeing = ?, conflicting = ?
                            WHERE id = ? AND status IN ('PENDING', 'BLOCKED')""")
                    .params(toJson(emr), s.score(), s.band().name(), s.agreeing().toArray(String[]::new),
                            s.conflicting().toArray(String[]::new), existing.get()).update();
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO matching.candidate (id, system, emr_uuid, patient_id, emr_record, score, band, agreeing, conflicting, status)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""")
                .params(id, system, emr.emrUuid(), p.id(), toJson(emr), s.score(), s.band().name(),
                        s.agreeing().toArray(String[]::new), s.conflicting().toArray(String[]::new), status).update();
        return id;
    }

    private List<Candidate> candidates(String where, Object... params) {
        return jdbc.sql("SELECT c.* FROM matching.candidate c WHERE " + where).params(List.of(params))
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getString("system"), rs.getString("emr_uuid"),
                        rs.getObject("patient_id", UUID.class), fromJson(rs.getString("emr_record"), EmrPatient.class),
                        rs.getDouble("score"), rs.getString("band"), strings(rs.getArray("agreeing")),
                        strings(rs.getArray("conflicting")), rs.getString("status"), rs.getString("decided_by"),
                        rs.getString("reason"))).list();
    }

    private List<Link> links(String where, Object... params) {
        return jdbc.sql("SELECT * FROM matching.link WHERE " + where).params(List.of(params))
                .query((rs, n) -> link(rs)).list();
    }

    private Link link(ResultSet rs) throws SQLException {
        return new Link(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getString("system"),
                rs.getString("emr_uuid"),
                rs.getDouble("score"), rs.getString("decided_by"), rs.getString("reason"),
                rs.getTimestamp("decided_at").toInstant(),
                fromJson(rs.getString("written_identifiers"), new TypeReference<Map<String, String>>() { }));
    }

    private Resolution count(Resolution r) {
        Metrics.counter("teleclinic.identity.considered", "outcome", r.outcome()).increment();
        return r;
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    private String toJson(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T fromJson(String v, Class<T> type) {
        try {
            return json.readValue(v, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T fromJson(String v, TypeReference<T> type) {
        try {
            return json.readValue(v, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
