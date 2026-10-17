package rw.teleclinic.core.channels;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.consent.ConsentService;
import rw.teleclinic.core.consultation.ConsultationService;
import rw.teleclinic.core.consultation.ConsultationView;
import rw.teleclinic.core.identity.PhoneLinks;
import rw.teleclinic.core.patientindex.PatientDirectory;
import rw.teleclinic.core.shared.ApiException;

/**
 * What the USSD gateway calls, one call per menu step. Internal only: blocked at the public edge and authenticated
 * with the gateway key; every call after the PIN also carries the short-lived channel token.
 */
@RestController
class UssdController {

    record Unlock(String msisdn, String pin) {
    }

    record Consent(String token, UUID patientId, boolean aiSupport) {
    }

    record Book(String token, UUID patientId, List<String> symptoms, Integer durationDays) {
    }

    record Pay(String token, UUID consultationId) {
    }

    private final PhoneLinks phones;
    private final PatientDirectory patients;
    private final ConsentService consents;
    private final ConsultationService consultations;
    private final byte[] gatewayKey;

    UssdController(PhoneLinks phones, PatientDirectory patients, ConsentService consents,
                   ConsultationService consultations, ChannelProperties props) {
        this.phones = phones;
        this.patients = patients;
        this.consents = consents;
        this.consultations = consultations;
        this.gatewayKey = props.gatewayKey().getBytes(StandardCharsets.UTF_8);
    }

    /** Phone number and PIN in; a channel token and the people this caller may book for out. */
    @PostMapping("/internal/ussd/unlock")
    Map<String, Object> unlock(@RequestHeader(value = "X-Gateway-Key", required = false) String key, @RequestBody Unlock req) {
        gateway(key);
        PhoneLinks.ChannelSession session = phones.unlock(req.msisdn(), req.pin());
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> people = patients.actableBy(session.subject()).stream().map(a -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("patientId", a.patient().id());
            p.put("firstName", a.patient().givenName());
            p.put("relation", a.relation());
            p.put("ageBand", a.patient().ageBand(today));
            p.put("hasConsent", consents.active(a.patient().id(), session.subject()).isPresent());
            return p;
        }).toList();
        return Map.of("token", session.token(), "expiresAt", session.expiresAt(), "people", people);
    }

    @PostMapping("/internal/ussd/consent")
    Map<String, Object> consent(@RequestHeader(value = "X-Gateway-Key", required = false) String key, @RequestBody Consent req) {
        gateway(key);
        PhoneLinks.ChannelSession s = phones.resolve(req.token());
        List<String> purposes = req.aiSupport() ? ConsentService.DEFAULT_PURPOSES : List.of("TREAT", "PAYMENT");
        ConsentService.Consent c = consents.give(s.subject(), req.patientId(), purposes);
        return Map.of("status", c.status(), "performerType", c.performerType(), "purposes", c.purposes());
    }

    /** Booking with the rules answer in the same step, so the advice shows in the same USSD session. */
    @PostMapping("/internal/ussd/book")
    Map<String, Object> book(@RequestHeader(value = "X-Gateway-Key", required = false) String key, @RequestBody Book req) {
        gateway(key);
        PhoneLinks.ChannelSession s = phones.resolve(req.token());
        ConsultationView c = consultations.book(s.subject(), new ConsultationService.BookingRequest(
                req.patientId(), "USSD", req.symptoms(), req.durationDays(), false, null), null);
        return Map.of("consultationId", c.id(), "urgency", c.urgency(), "advice", c.patientAdvice(), "redFlags", c.redFlags());
    }

    /** Mobile-money push to the caller's own phone; the USSD session can end before the payment is confirmed. */
    @PostMapping("/internal/ussd/pay")
    Map<String, Object> pay(@RequestHeader(value = "X-Gateway-Key", required = false) String key, @RequestBody Pay req) {
        gateway(key);
        PhoneLinks.ChannelSession s = phones.resolve(req.token());
        ConsultationService.PaymentRequestResult r = consultations.requestPayment(s.subject(), req.consultationId(), s.msisdn());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amount", r.amount());
        out.put("currency", r.currency());
        out.put("insured", r.insured());
        out.put("scheme", r.coverageScheme());
        return out;
    }

    private void gateway(String key) {
        if (key == null || !MessageDigest.isEqual(gatewayKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "gateway-key", "Unknown caller");
        }
    }
}
