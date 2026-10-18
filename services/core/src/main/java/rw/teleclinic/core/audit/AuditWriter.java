package rw.teleclinic.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The audit writer: reads audit.events (the application interceptor, through the outbox) and audit.db (pgAudit
 * lines shipped from the PostgreSQL logs) and appends both to the chain. One consumer group for all core instances.
 */
@Component
class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);
    private final AuditChain chain;
    private final ObjectMapper json;

    AuditWriter(AuditChain chain, ObjectMapper json) {
        this.chain = chain;
        this.json = json;
    }

    @KafkaListener(id = "audit-writer", topics = "audit.events", groupId = "audit-writer", containerFactory = "auditListeners",
            autoStartup = "#{'${teleclinic.audit.writer:kafka}' == 'kafka'}")
    void application(String message) {
        AuditEntry entry;
        try {
            entry = json.readValue(message, AuditEntry.class);
        } catch (Exception e) {
            log.warn("Skipping a message on audit.events that is not an audit entry: {}", e.getMessage());
            return;
        }
        chain.append(entry);
    }

    @KafkaListener(id = "audit-db", topics = "audit.db", groupId = "audit-writer", containerFactory = "auditListeners",
            autoStartup = "#{'${teleclinic.audit.writer:kafka}' == 'kafka'}")
    void database(String message) {
        JsonNode shipped;
        try {
            shipped = json.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping an unreadable message on audit.db: {}", e.getMessage());
            return;
        }
        String node = shipped.path("container_name").asText("").replaceAll("^/?teleclinic-|-1$", "");
        PgAuditLine.parse(shipped.path("message").asText(""), node).ifPresent(chain::append);
    }
}
