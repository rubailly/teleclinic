package rw.teleclinic.core.emrsync;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Per record: TeleClinic id, EMR id, the EMR's version and the content hash last seen. Plus a short event log for operators. */
@Component
class SyncLedger {

    record Entry(String resourceType, String teleclinicId, String emrUuid, String emrVersion, String contentHash,
                 String direction, Instant syncedAt) {
    }

    private final JdbcClient jdbc;

    SyncLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void record(String resourceType, String teleclinicId, String emrUuid, String version, String hash, String direction) {
        // The EMR id is unique per resource type: a previous mapping of the same EMR record is replaced
        jdbc.sql("DELETE FROM sync.ledger WHERE resource_type = ? AND emr_uuid = ? AND teleclinic_id <> ?")
                .params(resourceType, emrUuid, teleclinicId).update();
        jdbc.sql("""
                        INSERT INTO sync.ledger (resource_type, teleclinic_id, emr_uuid, emr_version, content_hash, direction, synced_at)
                        VALUES (?, ?, ?, ?, ?, ?, now())
                        ON CONFLICT (resource_type, teleclinic_id) DO UPDATE SET emr_uuid = excluded.emr_uuid,
                            emr_version = excluded.emr_version, content_hash = excluded.content_hash,
                            direction = excluded.direction, synced_at = now()""")
                .params(resourceType, teleclinicId, emrUuid, version, hash, direction).update();
    }

    void forget(String resourceType, String teleclinicId) {
        jdbc.sql("DELETE FROM sync.ledger WHERE resource_type = ? AND teleclinic_id = ?").params(resourceType, teleclinicId).update();
    }

    Optional<Entry> byTeleclinicId(String resourceType, String teleclinicId) {
        return jdbc.sql("SELECT * FROM sync.ledger WHERE resource_type = ? AND teleclinic_id = ?").params(resourceType, teleclinicId)
                .query((rs, n) -> entry(rs)).optional();
    }

    Optional<Entry> byEmrUuid(String resourceType, String emrUuid) {
        return jdbc.sql("SELECT * FROM sync.ledger WHERE resource_type = ? AND emr_uuid = ?").params(resourceType, emrUuid)
                .query((rs, n) -> entry(rs)).optional();
    }

    void event(String direction, String resourceType, String teleclinicId, String emrUuid, String outcome, String detail) {
        jdbc.sql("INSERT INTO sync.event (direction, resource_type, teleclinic_id, emr_uuid, outcome, detail) VALUES (?, ?, ?, ?, ?, ?)")
                .params(direction, resourceType, teleclinicId, emrUuid, outcome, detail).update();
    }

    Map<String, Object> view(Entry e, String externalSystem) {
        return Map.of("resourceType", e.resourceType(), "teleclinicId", e.teleclinicId(), "externalSystem", externalSystem,
                "externalId", e.emrUuid(), "contentHash", e.contentHash(),
                "origin", "OUTBOUND".equals(e.direction()) ? "teleclinic" : "external",
                "lastSyncedAt", e.syncedAt().toString(), "status", "IN_SYNC",
                "lastSyncedVersion", e.emrVersion() == null ? "" : e.emrVersion());
    }

    private static Entry entry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getString("resource_type"), rs.getString("teleclinic_id"), rs.getString("emr_uuid"),
                rs.getString("emr_version"), rs.getString("content_hash"), rs.getString("direction"),
                rs.getTimestamp("synced_at").toInstant());
    }
}
