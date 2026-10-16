package rw.teleclinic.core.consultation;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The 10-minute timer on PENDING_PAYMENT. Each consultation is handled in its own transaction with
 * SKIP LOCKED, so blue and green instances running side by side never process the same one twice.
 */
@Component
class PaymentTimer {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimer.class);
    private final ConsultationService consultations;

    PaymentTimer(ConsultationService consultations) {
        this.consultations = consultations;
    }

    @Scheduled(fixedDelayString = "${teleclinic.consultation.timer-interval:PT15S}", initialDelayString = "${teleclinic.consultation.timer-initial-delay:PT10S}")
    void expireOverdue() {
        for (UUID id : consultations.overdue(50)) {
            try {
                consultations.expireIfOverdue(id);
            } catch (RuntimeException e) {
                log.warn("Payment timer could not handle {}: {}", id, e.getMessage());
            }
        }
    }
}
