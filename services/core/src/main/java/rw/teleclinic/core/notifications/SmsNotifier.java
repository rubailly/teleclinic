package rw.teleclinic.core.notifications;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import rw.teleclinic.core.consultation.ConsultationEvents.ConsultationExpired;
import rw.teleclinic.core.consultation.ConsultationEvents.ConsultationPaid;
import rw.teleclinic.core.patientindex.Patient;
import rw.teleclinic.core.patientindex.PatientDirectory;

/**
 * SMS confirmations through the telecom aggregator. On USSD the session usually ends before the payment is
 * confirmed, so the SMS is how the caller learns they are in the queue. Runs after commit, tracked by the event
 * registry: a failed send is retried rather than lost.
 */
@Component
class SmsNotifier {

    private static final Logger log = LoggerFactory.getLogger(SmsNotifier.class);
    private final PatientDirectory patients;
    private final RestClient http;

    SmsNotifier(PatientDirectory patients, NotificationProperties props, RestClient.Builder builder) {
        this.patients = patients;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(3));
        this.http = builder.clone().baseUrl(props.smsUrl()).requestFactory(timeouts)
                .defaultHeader("X-Gateway-Key", props.smsKey()).build();
    }

    @ApplicationModuleListener
    void on(ConsultationPaid paid) {
        String who = patients.findById(paid.patientId()).map(Patient::givenName).orElse("The patient");
        send(paid.bookedBySubject(), "TeleClinic: payment of " + String.format(Locale.ROOT, "%,d", new java.math.BigDecimal(paid.amount()).longValue()) + " " + paid.currency() + " received. "
                + who + " is in the queue (" + paid.urgency().replace('_', '-').toLowerCase()
                + "). A clinician will call this number.");
    }

    @ApplicationModuleListener
    void on(ConsultationExpired expired) {
        send(expired.bookedBySubject(), "TeleClinic: the payment time ran out, so the booking was cancelled. "
                + "Dial *909*8# to book again.");
    }

    private void send(String subject, String text) {
        String phone = patients.findBySubject(subject).map(Patient::phone).orElse(null);
        if (phone == null) {
            log.info("No phone on record for the booker; SMS skipped");
            return;
        }
        http.post().uri("/sms/send").body(Map.of("to", phone, "text", text)).retrieve().toBodilessEntity();
        log.info("SMS sent ({} characters)", text.length());
    }
}
