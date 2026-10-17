package rw.teleclinic.core.consultation;

import jakarta.validation.Valid;
import java.util.List;
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

@RestController
class ConsultationController {

    private final ConsultationService consultations;

    ConsultationController(ConsultationService consultations) {
        this.consultations = consultations;
    }

    @PostMapping("/api/consultations")
    @ResponseStatus(HttpStatus.CREATED)
    ConsultationView book(@AuthenticationPrincipal OidcUser user,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                          @Valid @RequestBody ConsultationService.BookingRequest request) {
        return consultations.book(user.getSubject(), request, idempotencyKey);
    }

    @PostMapping("/api/consultations/{id}/payment")
    ConsultationService.PaymentRequestResult pay(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id) {
        return consultations.requestPayment(user.getSubject(), id);
    }

    @GetMapping("/api/consultations/{id}")
    ConsultationView get(@AuthenticationPrincipal OidcUser user, Authentication auth, @PathVariable UUID id) {
        return consultations.viewFor(user.getSubject(), isStaff(auth), id);
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
