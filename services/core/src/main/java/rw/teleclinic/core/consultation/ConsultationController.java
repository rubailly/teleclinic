package rw.teleclinic.core.consultation;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

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

    /** Where IremboPay's hosted checkout sends the browser back. Refreshes until the payment is confirmed. */
    @GetMapping(value = "/payment-return", produces = MediaType.TEXT_HTML_VALUE)
    String paymentReturn(@AuthenticationPrincipal OidcUser user, Authentication auth,
                         @RequestParam("consultation") UUID id) {
        ConsultationView c = consultations.viewFor(user.getSubject(), isStaff(auth), id);
        boolean waiting = c.state().equals("PENDING_PAYMENT");
        String message = switch (c.state()) {
            case "QUEUED" -> "Paid. You are in the queue; a clinician will contact you.";
            case "PENDING_PAYMENT" -> "Waiting for IremboPay to confirm the payment...";
            case "EXPIRED" -> "The payment window closed. You can book again.";
            default -> "Status: " + c.state();
        };
        return """
                <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                %s<title>TeleClinic payment</title></head>
                <body style="font-family:sans-serif;max-width:30rem;margin:2rem auto;padding:0 1rem">
                <h1>%s</h1><p>Consultation <code>%s</code>, urgency %s.</p><p>%s</p></body></html>
                """.formatted(waiting ? "<meta http-equiv=\"refresh\" content=\"2\">" : "",
                HtmlUtils.htmlEscape(message), c.id(), HtmlUtils.htmlEscape(String.valueOf(c.urgency())),
                HtmlUtils.htmlEscape(String.valueOf(c.patientAdvice())));
    }

    private static boolean isStaff(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_clinician") || a.getAuthority().equals("ROLE_admin"));
    }
}
