package rw.teleclinic.core.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-verifies the chain in the background; a break raises the "audit chain broken" alert (design: pages security). */
@Component
class AuditMonitor implements MeterBinder {

    private final AuditChain chain;
    private volatile AuditChain.Status last;

    AuditMonitor(AuditChain chain) {
        this.chain = chain;
    }

    @Scheduled(fixedDelayString = "${teleclinic.audit.verify-every:PT30S}", initialDelayString = "PT20S")
    void verify() {
        try {
            last = chain.verify();
        } catch (RuntimeException e) {
            // database unavailable (failover): keep the last result
        }
    }

    /** An auditor's check counts too, so the alert does not wait for the next background run. */
    void update(AuditChain.Status status) {
        last = status;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("teleclinic.audit.chain.intact", this, m -> m.last == null ? Double.NaN : m.last.intact() ? 1 : 0)
                .description("1 when the last verification found the audit chain intact").register(registry);
        Gauge.builder("teleclinic.audit.chain.length", this, m -> m.last == null ? Double.NaN : m.last.checked())
                .description("Entries checked by the last verification").register(registry);
    }
}
