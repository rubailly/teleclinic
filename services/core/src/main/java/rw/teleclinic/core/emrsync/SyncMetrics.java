package rw.teleclinic.core.emrsync;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Queue depth, dead letters and the age of the oldest waiting write: the sync dashboard and the backlog alert. */
@Component
class SyncMetrics implements MeterBinder {

    private final JdbcClient jdbc;

    SyncMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("teleclinic.sync.outbound.pending", this, m -> m.q("count(*) FROM sync.outbound WHERE status = 'PENDING'"))
                .description("Writes waiting for the facility EMR").register(registry);
        Gauge.builder("teleclinic.sync.outbound.dead", this, m -> m.q("count(*) FROM sync.outbound WHERE status = 'DEAD'"))
                .description("Writes that gave up after the retry limit (dead letters)").register(registry);
        Gauge.builder("teleclinic.sync.outbound.oldest.seconds", this,
                        m -> m.q("coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM sync.outbound WHERE status = 'PENDING'"))
                .description("Age of the oldest write waiting for the EMR").register(registry);
    }

    private double q(String expression) {
        try {
            return jdbc.sql("SELECT " + expression).query(Double.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
