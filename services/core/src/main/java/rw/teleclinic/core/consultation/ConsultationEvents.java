package rw.teleclinic.core.consultation;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * Events other systems care about. They are stored in the event publication table in the same transaction as
 * the state change (the outbox) and sent to Kafka after commit, keyed by patient id so one patient's events stay
 * in order.
 */
public final class ConsultationEvents {

    private ConsultationEvents() {
    }

    @Externalized("consultation.paid::#{#this.patientId()}")
    public record ConsultationPaid(UUID consultationId, UUID patientId, String bookedBySubject, String channel, String urgency,
                                   String amount, String currency, Instant paidAt) {
    }

    @Externalized("consultation.expired::#{#this.patientId()}")
    public record ConsultationExpired(UUID consultationId, UUID patientId, String bookedBySubject, Instant expiredAt) {
    }
}
