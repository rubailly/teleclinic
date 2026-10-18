package rw.teleclinic.core.shared;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Events committed with a state change but not yet delivered to Kafka (the outbox), read on each scrape. */
@Component
class OutboxMetrics implements MeterBinder {

    private final JdbcClient jdbc;

    OutboxMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("teleclinic.outbox.pending", this, m -> m.query("count(*)"))
                .description("Outbox events not yet published").register(registry);
        Gauge.builder("teleclinic.outbox.oldest.pending.seconds", this,
                        m -> m.query("coalesce(extract(epoch FROM now() - min(publication_date)), 0)"))
                .description("Age of the oldest unpublished outbox event").register(registry);
    }

    private double query(String expression) {
        try {
            return jdbc.sql("SELECT " + expression + " FROM public.event_publication WHERE completion_date IS NULL")
                    .query(Double.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
