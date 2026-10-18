package rw.teleclinic.core.emrsync;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.shared.ApiException;

/**
 * What the sync gateway calls. /sync/inbound and /sync/ledger follow api/teleclinic-interop-api.yaml; the outbound
 * queue is internal (the gateway pulls it, one EMR per workflow: ?system=ebuzima or ?system=openmrs-...). The public edge refuses both /internal/* and /sync/inbound.
 */
@RestController
class SyncController {

    record Done(String outcome, JsonNode emrPatient, String emrEncounterUuid) {
    }

    record Failed(String error) {
    }

    record InboundChange(String externalSystem, JsonNode resource, String origin, String contentHash, String sourceVersion) {
    }

    record Cursor(Instant polledAt) {
    }

    private final SyncQueue queue;
    private final InboundSync inbound;
    private final SyncLedger ledger;
    private final SyncProperties props;

    SyncController(SyncQueue queue, InboundSync inbound, SyncLedger ledger, SyncProperties props) {
        this.queue = queue;
        this.inbound = inbound;
        this.ledger = ledger;
        this.props = props;
    }

    @GetMapping("/internal/sync/outbound/due")
    Map<String, Object> due(@RequestHeader(value = "X-Sync-Key", required = false) String key,
                            @RequestParam String system, @RequestParam(defaultValue = "20") int limit) {
        gateway(key);
        return Map.of("items", queue.due(system, limit));
    }

    @PostMapping("/internal/sync/outbound/{id}/done")
    Map<String, Object> done(@RequestHeader(value = "X-Sync-Key", required = false) String key, @PathVariable UUID id,
                             @RequestBody Done body) {
        gateway(key);
        queue.done(id, body.outcome(), body.emrPatient(), body.emrEncounterUuid());
        return Map.of("status", "DONE");
    }

    @PostMapping("/internal/sync/outbound/{id}/failed")
    Map<String, Object> failed(@RequestHeader(value = "X-Sync-Key", required = false) String key, @PathVariable UUID id,
                               @RequestBody Failed body) {
        gateway(key);
        queue.failed(id, body.error());
        return Map.of("status", "RETRY_SCHEDULED");
    }

    @PostMapping("/internal/sync/outbound/replay")
    Map<String, Object> replay(@RequestHeader(value = "X-Sync-Key", required = false) String key, @RequestParam String system) {
        gateway(key);
        return Map.of("replayed", queue.replay(system));
    }

    @GetMapping("/internal/sync/inbound/since")
    Map<String, Object> since(@RequestHeader(value = "X-Sync-Key", required = false) String key, @RequestParam String system) {
        gateway(key);
        return Map.of("since", inbound.since(system).toString());
    }

    @PostMapping("/internal/sync/inbound/cursor")
    Map<String, Object> cursor(@RequestHeader(value = "X-Sync-Key", required = false) String key, @RequestParam String system,
                               @RequestBody Cursor body) {
        gateway(key);
        inbound.advance(system, body.polledAt());
        return Map.of("cursor", body.polledAt().toString());
    }

    /** One change detected in the EMR. 200 when it is an echo of our own write, 202 when it was taken in. */
    @PostMapping("/sync/inbound")
    ResponseEntity<Map<String, Object>> inbound(@RequestHeader(value = "X-Sync-Key", required = false) String key,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                @RequestBody InboundChange change) {
        gateway(key);
        if (change.resource() == null || !"Patient".equals(change.resource().path("resourceType").asText())) {
            throw ApiException.unprocessable("invalid", "resource must be a FHIR Patient");
        }
        InboundSync.Outcome o = inbound.receive(change.externalSystem(), change.resource());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("outcome", o.outcome());
        body.put("teleclinicPatient", o.patientId() == null ? null : "Patient/" + o.patientId());
        body.put("detail", o.detail());
        return ResponseEntity.status(o.echo() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(body);
    }

    /** One EMR's entry (?externalSystem=), or else the most recently synced one. */
    @GetMapping("/sync/ledger/{resourceType}/{id}")
    Map<String, Object> ledgerEntry(@PathVariable String resourceType, @PathVariable String id,
                                    @RequestParam(required = false) String externalSystem) {
        return (externalSystem == null ? ledger.byTeleclinicId(resourceType, id).stream().findFirst()
                        : ledger.byTeleclinicId(externalSystem, resourceType, id))
                .map(ledger::view)
                .orElseThrow(() -> ApiException.notFound("No ledger entry for " + resourceType + "/" + id));
    }

    private void gateway(String key) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.key().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unknown-caller", "Not the sync gateway");
        }
    }
}
