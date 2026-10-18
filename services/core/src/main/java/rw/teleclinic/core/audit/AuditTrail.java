package rw.teleclinic.core.audit;

import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import rw.teleclinic.core.shared.CorrelationIdFilter;

/** The audit module's entry point for other modules: record what an actor did to which patient's data, and why. */
@Service
public class AuditTrail {

    private final AuditChain chain;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate ownTransaction;
    private final boolean direct;

    AuditTrail(AuditChain chain, ApplicationEventPublisher events, PlatformTransactionManager transactions,
               @Value("${teleclinic.audit.writer:kafka}") String writer) {
        this.chain = chain;
        this.events = events;
        this.ownTransaction = new TransactionTemplate(transactions);
        // Its own transaction: a denied request rolls back, its audit record must not
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.direct = "direct".equals(writer);
        // Start every series at zero: Prometheus cannot see an increase on a counter whose first sample is already 1,
        // so the first break-glass read would not raise the alert
        for (ClinicalAccess.Purpose p : ClinicalAccess.Purpose.values()) {
            for (String outcome : new String[] {AuditEntry.SUCCESS, AuditEntry.DENIED}) {
                Metrics.counter("teleclinic.audit.records", "source", AuditEntry.APPLICATION, "outcome", outcome,
                        "purpose", p.name());
            }
        }
    }

    /** A record from the application layer. resourceId and patientId may be null (a search, a chain check). */
    public void record(Actor actor, String action, String resourceType, String resourceId, UUID patientId, String purpose,
                       String consentBasis, String outcome, String reason, Map<String, String> detail) {
        record(new AuditEntry(UUID.randomUUID(), Instant.now(), AuditEntry.APPLICATION, actor.subject(), actor.role(),
                actor.name(), action, resourceType, resourceId, patientId, purpose, consentBasis, outcome, reason,
                MDC.get(CorrelationIdFilter.MDC_KEY), detail));
    }

    void record(AuditEntry entry) {
        if (direct) {
            chain.append(entry);
        } else {
            ownTransaction.executeWithoutResult(s -> events.publishEvent(entry));
        }
        Metrics.counter("teleclinic.audit.records", "source", entry.source(), "outcome", entry.outcome(),
                "purpose", entry.purpose() == null ? "none" : entry.purpose()).increment();
    }
}
