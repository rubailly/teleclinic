package rw.teleclinic.core.consultation;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.audit.Actor;
import rw.teleclinic.core.audit.AuditTrail;
import rw.teleclinic.core.audit.ClinicalAccess;

@RestController
class ConsultationController {

    private final ConsultationService consultations;
    private final AuditTrail audit;
    private final ClinicalAccess access;

    ConsultationController(ConsultationService consultations, AuditTrail audit, ClinicalAccess access) {
        this.consultations = consultations;
        this.audit = audit;
        this.access = access;
    }

    @PostMapping("/api/consultations")
    @ResponseStatus(HttpStatus.CREATED)
    ConsultationView book(Authentication who, @AuthenticationPrincipal OidcUser user,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                          @Valid @RequestBody ConsultationService.BookingRequest request) {
        ConsultationView v = consultations.book(user.getSubject(), request, idempotencyKey);
        audit.record(Actor.of(who), "CREATE", "Consultation", v.id().toString(), v.patientId(), "PATIENT_ACCESS",
                "data-subject", "SUCCESS", null, Map.of("channel", String.valueOf(request.channel())));
        return v;
    }

    @PostMapping("/api/consultations/{id}/payment")
    ConsultationService.PaymentRequestResult pay(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id) {
        return consultations.requestPayment(user.getSubject(), id);
    }

    @GetMapping("/api/consultations/{id}")
    ConsultationView get(@AuthenticationPrincipal OidcUser user, Authentication auth, @PathVariable UUID id,
                         @RequestHeader(value = "X-Purpose-Of-Use", required = false) String purpose,
                         @RequestHeader(value = "X-Break-Glass-Reason", required = false) String reason) {
        if (isStaff(auth)) {
            // Staff read someone else's record: same policy as every clinical read (purpose, consent)
            return access.read(auth, consultations.view(id).patientId(), "Consultation", id.toString(), purpose, reason,
                    () -> consultations.viewFor(user.getSubject(), true, id));
        }
        ConsultationView v = consultations.viewFor(user.getSubject(), false, id);
        audit.record(Actor.of(auth), "READ", "Consultation", id.toString(), v.patientId(), "PATIENT_ACCESS", "data-subject",
                "SUCCESS", null, Map.of());
        return v;
    }

    @GetMapping("/api/consultations")
    List<ConsultationView> mine(@AuthenticationPrincipal OidcUser user) {
        return consultations.bookedBy(user.getSubject());
    }

    private static boolean isStaff(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_clinician") || a.getAuthority().equals("ROLE_admin"));
    }
}
