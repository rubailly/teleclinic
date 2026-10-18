package rw.teleclinic.core.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.shared.ApiException;

/**
 * Reading the trail: the patient's "who accessed my record", the auditor's explorer and chain check, and the
 * FHIR AuditEvent search (api/teleclinic-interop-api.yaml). Reading the trail is itself recorded.
 */
@RestController
class AuditController {

    private final JdbcClient jdbc;
    private final AuditChain chain;
    private final AuditTrail audit;
    private final ClinicalAccess access;
    private final AuditMonitor monitor;

    AuditController(JdbcClient jdbc, AuditChain chain, AuditTrail audit, ClinicalAccess access, AuditMonitor monitor) {
        this.jdbc = jdbc;
        this.chain = chain;
        this.audit = audit;
        this.access = access;
        this.monitor = monitor;
    }

    /** Everyone who read or changed this patient's data, for the patient or their guardian. */
    @GetMapping("/api/me/patients/{patientId}/access-log")
    Map<String, Object> accessLog(Authentication who, @PathVariable UUID patientId) {
        String me = Actor.of(who).subject();
        return access.read(who, patientId, "AuditEvent", null, "PATIENT_ACCESS", null, () -> {
            List<Map<String, Object>> entries = jdbc.sql("""
                            SELECT seq, recorded_at, actor, actor_name, actor_role, action, resource_type, purpose, outcome, reason
                            FROM audit.event
                            WHERE patient_id = ? AND NOT (actor = ? AND resource_type = 'AuditEvent')
                            ORDER BY seq DESC LIMIT 200""")
                    .params(patientId, me)
                    .query((rs, n) -> {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("at", rs.getTimestamp("recorded_at").toInstant());
                        e.put("who", rs.getString("actor_name"));
                        e.put("role", rs.getString("actor_role"));
                        e.put("you", me.equals(rs.getString("actor")));
                        e.put("action", rs.getString("action"));
                        e.put("resource", rs.getString("resource_type"));
                        e.put("purpose", rs.getString("purpose"));
                        e.put("outcome", rs.getString("outcome"));
                        e.put("breakGlassReason", "BREAK_GLASS".equals(rs.getString("purpose")) ? rs.getString("reason") : null);
                        return e;
                    }).list();
            return Map.of("patientId", patientId, "entries", entries);
        });
    }

    /** The auditor's view: the latest entries from every source, with their place in the chain. */
    @GetMapping("/api/audit/events")
    Map<String, Object> events(Authentication who, @RequestParam(required = false) String source,
                               @RequestParam(required = false) UUID patientId, @RequestParam(defaultValue = "100") int limit,
                               @RequestParam(defaultValue = "false") boolean hideSearches) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE true");
        if (source != null && !source.isBlank()) {
            where.append(" AND source = ?");
            params.add(source.toUpperCase());
        }
        if (patientId != null) {
            where.append(" AND patient_id = ?");
            params.add(patientId);
        }
        if (hideSearches) {
            // Worklist polling is recorded on every call; the explorer can leave it out of the view
            where.append(" AND action <> 'SEARCH'");
        }
        params.add(Math.min(Math.max(limit, 1), 500));
        List<Map<String, Object>> entries = jdbc.sql("SELECT * FROM audit.event " + where + " ORDER BY seq DESC LIMIT ?")
                .params(params).query((rs, n) -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    for (String col : List.of("seq", "source", "actor", "actor_name", "actor_role", "action", "resource_type",
                            "resource_id", "patient_id", "purpose", "consent_basis", "outcome", "reason", "correlation_id")) {
                        e.put(col, rs.getObject(col));
                    }
                    e.put("recorded_at", rs.getTimestamp("recorded_at").toInstant());
                    e.put("detail", chain.entry(rs).detail());
                    e.put("hash", rs.getString("hash"));
                    e.put("prev_hash", rs.getString("prev_hash"));
                    return e;
                }).list();
        audit.record(Actor.of(who), "SEARCH", "AuditEvent", null, patientId, "OPERATIONS", "policy:operations",
                AuditEntry.SUCCESS, null, source == null ? Map.of() : Map.of("source", source));
        return Map.of("entries", entries);
    }

    /** Recomputes the whole chain now. */
    @GetMapping("/api/audit/verify")
    AuditChain.Status verify(Authentication who) {
        AuditChain.Status status = chain.verify();
        monitor.update(status);
        audit.record(Actor.of(who), "VERIFY", "AuditChain", null, null, "OPERATIONS", "policy:operations",
                AuditEntry.SUCCESS, null, Map.of("result", status.status(), "checked", Long.toString(status.checked())));
        return status;
    }

    /** FHIR search: GET /fhir/AuditEvent?entity=Patient/{id}. AuditEvent shaped after IHE BALP. */
    @GetMapping(value = "/fhir/AuditEvent", produces = "application/fhir+json")
    ResponseEntity<Map<String, Object>> fhirSearch(Authentication who, @RequestParam String entity,
                                                   @RequestHeader(value = "X-Purpose-Of-Use", required = false) String purpose) {
        if (!entity.startsWith("Patient/")) {
            throw ApiException.unprocessable("invalid", "entity must be Patient/{id}");
        }
        UUID patientId;
        try {
            patientId = UUID.fromString(entity.substring("Patient/".length()));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid", "entity must be Patient/{id}");
        }
        Map<String, Object> bundle = access.read(who, patientId, "AuditEvent", null, purpose == null ? "OPERATIONS" : purpose,
                null, () -> {
                    List<Map<String, Object>> resources = jdbc.sql("SELECT * FROM audit.event WHERE patient_id = ? ORDER BY seq DESC LIMIT 200")
                            .param(patientId).query((rs, n) -> fhir(rs.getLong("seq"), rs.getTimestamp("recorded_at").toInstant(),
                                    rs.getString("action"), rs.getString("outcome"), rs.getString("actor"), rs.getString("actor_name"),
                                    rs.getString("actor_role"), rs.getString("purpose"), rs.getString("resource_type"),
                                    rs.getString("resource_id"), patientId)).list();
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("resourceType", "Bundle");
                    b.put("type", "searchset");
                    b.put("total", resources.size());
                    b.put("entry", resources.stream().map(r -> Map.of("resource", r)).toList());
                    return b;
                });
        return ResponseEntity.ok().header("Content-Type", "application/fhir+json").body(bundle);
    }

    private static Map<String, Object> fhir(long seq, Instant at, String action, String outcome, String actor, String name,
                                            String role, String purpose, String resourceType, String resourceId, UUID patientId) {
        String code = switch (action) {
            case "CREATE" -> "C";
            case "DECIDE", "UPDATE" -> "U";
            case "SEARCH" -> "E";
            default -> "R";
        };
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("who", Map.of("identifier", Map.of("system", "https://account.irembo.example/sub", "value", actor),
                "display", name == null ? actor : name));
        agent.put("role", List.of(Map.of("text", role == null ? "unknown" : role)));
        agent.put("requestor", true);
        if (purpose != null) {
            agent.put("purposeOfUse", List.of(Map.of("coding", List.of(Map.of(
                    "system", "http://terminology.hl7.org/CodeSystem/v3-ActReason", "code", v3(purpose), "display", purpose)))));
        }
        List<Map<String, Object>> entities = new ArrayList<>();
        entities.add(Map.of("what", Map.of("reference", "Patient/" + patientId), "role",
                Map.of("system", "http://terminology.hl7.org/CodeSystem/object-role", "code", "1", "display", "Patient")));
        if (resourceType != null) {
            entities.add(Map.of("what", Map.of("display", resourceType + (resourceId == null ? "" : "/" + resourceId))));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "AuditEvent");
        r.put("id", Long.toString(seq));
        r.put("type", Map.of("system", "http://terminology.hl7.org/CodeSystem/audit-event-type", "code", "rest"));
        r.put("action", code);
        r.put("recorded", at.toString());
        r.put("outcome", "SUCCESS".equals(outcome) ? "0" : "4");
        r.put("agent", List.of(agent));
        r.put("source", Map.of("observer", Map.of("display", "TeleClinic core")));
        r.put("entity", entities);
        return r;
    }

    private static String v3(String purpose) {
        return switch (purpose) {
            case "TREAT" -> "TREAT";
            case "PAYMENT" -> "HPAYMT";
            case "OPERATIONS" -> "HOPERAT";
            case "PATIENT_ACCESS" -> "PATRQT";
            case "BREAK_GLASS" -> "ETREAT";
            default -> purpose;
        };
    }
}
