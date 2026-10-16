package rw.teleclinic.core.consent;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.shared.ApiException;

@RestController
class ConsentController {

    record ConsentRequest(List<String> purposes) {
    }

    private final ConsentService consents;

    ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @PostMapping("/api/patients/{patientId}/consents")
    @ResponseStatus(HttpStatus.CREATED)
    ConsentService.Consent give(@AuthenticationPrincipal OidcUser user, @PathVariable UUID patientId,
                                @RequestBody(required = false) ConsentRequest request) {
        List<String> purposes = request == null || request.purposes() == null || request.purposes().isEmpty()
                ? ConsentService.DEFAULT_PURPOSES : request.purposes();
        return consents.give(user.getSubject(), patientId, purposes);
    }

    @GetMapping("/api/patients/{patientId}/consents/active")
    ConsentService.Consent active(@AuthenticationPrincipal OidcUser user, @PathVariable UUID patientId) {
        return consents.active(patientId, user.getSubject())
                .orElseThrow(() -> ApiException.notFound("No active consent"));
    }

    @DeleteMapping("/api/patients/{patientId}/consents/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void withdraw(@AuthenticationPrincipal OidcUser user, @PathVariable UUID patientId) {
        consents.withdraw(user.getSubject(), patientId);
    }
}
