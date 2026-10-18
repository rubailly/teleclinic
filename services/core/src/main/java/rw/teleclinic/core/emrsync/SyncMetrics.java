package rw.teleclinic.core.emrsync;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Per EMR: queue depth, dead letters and the age of the oldest waiting write (the sync dashboard and the backlog alert). */
@Component
class SyncMetrics implements MeterBinder {

    private final JdbcClient jdbc;
    private final SyncProperties props;

    SyncMetrics(JdbcClient jdbc, SyncProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String emr : props.targets().keySet()) {
            Gauge.builder("teleclinic.sync.outbound.pending", this, m -> m.q(emr, "count(*) FROM sync.outbound WHERE target = ? AND status = 'PENDING'"))
                    .tag("emr", emr).description("Writes waiting for the EMR").register(registry);
            Gauge.builder("teleclinic.sync.outbound.dead", this, m -> m.q(emr, "count(*) FROM sync.outbound WHERE target = ? AND status = 'DEAD'"))
                    .tag("emr", emr).description("Writes that gave up after the retry limit (dead letters)").register(registry);
            Gauge.builder("teleclinic.sync.outbound.oldest.seconds", this, m -> m.q(emr,
                            "coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM sync.outbound WHERE target = ? AND status = 'PENDING'"))
                    .tag("emr", emr).description("Age of the oldest write waiting for the EMR").register(registry);
        }
    }

    private double q(String emr, String expression) {
        try {
            return jdbc.sql("SELECT " + expression).param(emr).query(Double.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
