package rw.teleclinic.core.triage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clinician queue and review (Triage API: suggestion, review, models). Access by role is enforced in
 * SecurityConfig: clinicians review, administrators operate the kill switch.
 */
@RestController
class ReviewController {

    record ReviewDecision(String decision, Urgency finalUrgency, String reason) {
    }

    record ModeChange(String reason) {
    }

    private final TriageCases cases;
    private final ModelControl models;

    ReviewController(TriageCases cases, ModelControl models) {
        this.cases = cases;
        this.models = models;
    }

    @GetMapping("/clinician")
    org.springframework.web.servlet.view.RedirectView page() {
        return new org.springframework.web.servlet.view.RedirectView("/clinician/index.html");
    }

    @GetMapping("/api/clinician/queue")
    Map<String, Object> queue() {
        ModelControl.ModelInfo model = models.active();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("rulesOnlyMode", !"ACTIVE".equals(model.mode()));
        body.put("generatedAt", Instant.now());
        body.put("cases", cases.queue());
        return body;
    }

    @GetMapping("/api/clinician/cases/{id}")
    Map<String, Object> caseDetail(@PathVariable UUID id) {
        return Map.of("case", cases.view(id), "provenance", cases.provenance(id));
    }

    @PostMapping("/api/clinician/cases/{id}/open")
    TriageCaseView open(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id) {
        return cases.openForReview(id, user.getFullName());
    }

    /** Triage API: GET /cases/{caseId}/suggestion */
    @GetMapping("/triage/v1/cases/{caseId}/suggestion")
    Map<String, Object> suggestion(@PathVariable UUID caseId) {
        TriageCaseView c = cases.view(caseId);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("caseId", c.consultationId());
        s.put("status", c.status());
        s.put("urgency", c.finalUrgency() != null ? c.finalUrgency() : c.mergedUrgency());
        s.put("rulesFloor", c.rulesUrgency());
        s.put("confidence", c.modelConfidence());
        s.put("contributingFactors", c.contributingFactors());
        s.put("modelVersion", c.modelVersion());
        s.put("reviewedBy", c.reviewedBy());
        s.put("reviewReason", c.reviewReason());
        return s;
    }

    /** Triage API: POST /cases/{caseId}/review (clinician only) */
    @PostMapping("/triage/v1/cases/{caseId}/review")
    TriageCaseView review(@AuthenticationPrincipal OidcUser user, @PathVariable UUID caseId,
                          @RequestBody ReviewDecision decision) {
        return cases.review(caseId, decision.decision(), decision.finalUrgency(), decision.reason(),
                user.getSubject(), user.getFullName());
    }

    @GetMapping("/triage/v1/models/active")
    ModelControl.ModelInfo active() {
        return models.active();
    }

    /** Triage API: POST /models/kill-switch (administrator only). New cases become rules-only at once. */
    @PostMapping("/triage/v1/models/kill-switch")
    ModelControl.ModelInfo killSwitch(@AuthenticationPrincipal OidcUser user, @RequestBody ModeChange change) {
        return models.setMode("DISABLED", reason(user, change));
    }

    @PostMapping("/triage/v1/models/activate")
    ModelControl.ModelInfo activate(@AuthenticationPrincipal OidcUser user, @RequestBody ModeChange change) {
        return models.setMode("ACTIVE", reason(user, change));
    }

    private static String reason(OidcUser user, ModeChange change) {
        String why = change == null || change.reason() == null || change.reason().isBlank() ? "no reason given" : change.reason();
        return why + " (by " + user.getPreferredUsername() + ")";
    }
}
