package rw.teleclinic.core.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The single place that writes the trail: sequence numbers, hashes, and the check that nothing was changed. */
@Service
class AuditChain {

    /** Result of a full verification. firstBrokenSeq is the first entry whose hash or link does not match. */
    record Status(String status, long checked, Long firstBrokenSeq, String problem, long headSeq, String headHash,
                  Instant checkedAt) {
        boolean intact() {
            return "INTACT".equals(status);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AuditChain.class);
    private static final TypeReference<Map<String, String>> DETAIL = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuditChain(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Appends one entry. Returns false if this event id is already in the trail (a redelivery). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean append(AuditEntry e) {
        // The row lock serialises writers: two core instances can never fork the chain
        Object[] head = jdbc.sql("SELECT seq, hash FROM audit.chain_head WHERE id = 1 FOR UPDATE")
                .query((rs, n) -> new Object[] {rs.getLong("seq"), rs.getString("hash")}).single();
        boolean seen = jdbc.sql("SELECT count(*) FROM audit.event WHERE event_id = ?").param(e.eventId())
                .query(Long.class).single() > 0;
        if (seen) {
            return false;
        }
        long seq = (Long) head[0] + 1;
        String previous = (String) head[1];
        String hash = AuditHash.of(seq, e, previous);
        jdbc.sql("""
                        INSERT INTO audit.event (seq, event_id, recorded_at, source, actor, actor_role, actor_name, action,
                            resource_type, resource_id, patient_id, purpose, consent_basis, outcome, reason, correlation_id,
                            detail, prev_hash, hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""")
                .params(seq, e.eventId(), Timestamp.from(e.recordedAt()), e.source(), e.actor(), e.actorRole(),
                        e.actorName(), e.action(), e.resourceType(), e.resourceId(), e.patientId(), e.purpose(),
                        e.consentBasis(), e.outcome(), e.reason(), e.correlationId(), toJson(e.detail()), previous, hash)
                .update();
        jdbc.sql("UPDATE audit.chain_head SET seq = ?, hash = ? WHERE id = 1").params(seq, hash).update();
        return true;
    }

    /**
     * Recomputes every hash in order. Catches a changed field, a deleted row and a reordered row. One snapshot for
     * the rows and the head, so an append while verifying cannot look like a removed entry.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Status verify() {
        long[] checked = {0};
        String[] previous = {AuditHash.GENESIS};
        Long[] broken = {null};
        String[] problem = {null};
        jdbc.sql("SELECT * FROM audit.event ORDER BY seq").query(rs -> {
            if (broken[0] != null) {
                return;
            }
            long seq = rs.getLong("seq");
            long expectedSeq = checked[0] + 1;
            if (seq != expectedSeq) {
                broken[0] = expectedSeq;
                problem[0] = "entry " + expectedSeq + " is missing";
                return;
            }
            AuditEntry e = entry(rs);
            String stored = rs.getString("hash");
            if (!previous[0].equals(rs.getString("prev_hash"))) {
                broken[0] = seq;
                problem[0] = "link to the previous entry does not match";
            } else if (!AuditHash.of(seq, e, previous[0]).equals(stored)) {
                broken[0] = seq;
                problem[0] = "content does not match its hash";
            }
            previous[0] = stored;
            checked[0] = seq;
        });
        Object[] head = jdbc.sql("SELECT seq, hash FROM audit.chain_head WHERE id = 1")
                .query((rs, n) -> new Object[] {rs.getLong("seq"), rs.getString("hash")}).single();
        long headSeq = (Long) head[0];
        String headHash = (String) head[1];
        if (broken[0] == null && (checked[0] != headSeq || !previous[0].equals(headHash))) {
            broken[0] = checked[0] + 1;
            problem[0] = "the end of the trail does not match the chain head (entries removed at the end)";
        }
        String status = broken[0] == null ? "INTACT" : "BROKEN";
        if (broken[0] != null) {
            log.error("Audit chain BROKEN at entry {}: {}", broken[0], problem[0]);
        }
        return new Status(status, checked[0], broken[0], problem[0], headSeq, headHash, Instant.now());
    }

    AuditEntry entry(ResultSet rs) throws SQLException {
        return new AuditEntry(rs.getObject("event_id", UUID.class), rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("source"), rs.getString("actor"), rs.getString("actor_role"), rs.getString("actor_name"),
                rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
                rs.getObject("patient_id", UUID.class), rs.getString("purpose"), rs.getString("consent_basis"),
                rs.getString("outcome"), rs.getString("reason"), rs.getString("correlation_id"),
                fromJson(rs.getString("detail")));
    }

    private String toJson(Map<String, String> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> fromJson(String detail) {
        try {
            return json.readValue(detail, DETAIL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
