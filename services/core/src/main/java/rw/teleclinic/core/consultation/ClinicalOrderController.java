package rw.teleclinic.core.consultation;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.shared.ApiException;
import rw.teleclinic.core.triage.TriageCases;

/** Orders, referrals and prescriptions. Refused until a clinician has finalised the triage (policy, not UI). */
@RestController
class ClinicalOrderController {

    record OrderRequest(@NotBlank String kind, @NotBlank String detail) {
    }

    private final JdbcClient jdbc;
    private final TriageCases triage;

    ClinicalOrderController(JdbcClient jdbc, TriageCases triage) {
        this.jdbc = jdbc;
        this.triage = triage;
    }

    @PostMapping("/api/clinician/consultations/{id}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> order(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id, @RequestBody OrderRequest req) {
        if (!triage.isFinal(id)) {
            throw ApiException.forbidden("triage-not-final",
                    "Triage is not final: a clinician must confirm or override the AI-proposed urgency first");
        }
        UUID orderId = UUID.randomUUID();
        jdbc.sql("INSERT INTO consultation.clinical_order (id, consultation_id, kind, detail, ordered_by) VALUES (?, ?, ?, ?, ?)")
                .params(orderId, id, req.kind(), req.detail(), user.getSubject()).update();
        return Map.of("orderId", orderId, "consultationId", id, "kind", req.kind(), "orderedBy", String.valueOf(user.getFullName()));
    }
}
