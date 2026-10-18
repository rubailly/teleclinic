package rw.teleclinic.core.triage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Clinical-safety gauges, read on each scrape: what waits for a clinician, what is late, and whether the model is on. */
@Component
class TriageMetrics implements MeterBinder {

    private final JdbcClient jdbc;
    private final ModelControl models;

    TriageMetrics(JdbcClient jdbc, ModelControl models) {
        this.jdbc = jdbc;
        this.models = models;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("teleclinic.triage.awaiting.review", count("status IN ('RULES_ONLY', 'AI_PROPOSED', 'UNDER_REVIEW')"),
                Supplier::get).strongReference(true).description("Triage cases waiting for a clinician").register(registry);
        Gauge.builder("teleclinic.triage.review.overdue", count("status IN ('RULES_ONLY', 'AI_PROPOSED', 'UNDER_REVIEW', "
                        + "'UNREVIEWED_TIMEOUT') AND sla_due_at < now()"), Supplier::get).strongReference(true)
                .description("Triage cases past their review deadline and not yet decided").register(registry);
        Gauge.builder("teleclinic.triage.model.active", models, m -> "ACTIVE".equals(m.active().mode()) ? 1 : 0)
                .description("1 when the triage model is active, 0 when switched off or unreachable").register(registry);
    }

    private Supplier<Double> count(String where) {
        return () -> {
            try {
                return jdbc.sql("SELECT count(*) FROM triage.triage_case WHERE " + where).query(Double.class).single();
            } catch (RuntimeException e) {
                return Double.NaN;  // database unreachable during a failover: a gap, not a zero
            }
        };
    }
}
