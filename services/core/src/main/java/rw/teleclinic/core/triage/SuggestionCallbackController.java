package rw.teleclinic.core.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.shared.ApiException;
import rw.teleclinic.core.shared.HmacSignature;

/**
 * The suggestionReady callback from the AI trust zone. Internal only: blocked at the public edge, and signed
 * with a secret only the model service and the core share (production adds mTLS). An invalid or unexpected
 * result is ignored, which leaves the case on rules only.
 */
@RestController
class SuggestionCallbackController {

    private static final Logger log = LoggerFactory.getLogger(SuggestionCallbackController.class);

    private final TriageCases cases;
    private final PseudonymVault vault;
    private final ObjectMapper json;
    private final HmacSignature signature;

    SuggestionCallbackController(TriageCases cases, PseudonymVault vault, ObjectMapper json, TriageProperties props) {
        this.cases = cases;
        this.vault = vault;
        this.json = json;
        this.signature = new HmacSignature(props.callbackSecret(), props.callbackTolerance());
    }

    @PostMapping("/internal/triage/suggestions")
    Map<String, String> receive(@RequestBody String body, @RequestHeader(value = "X-Signature", required = false) String sig)
            throws Exception {
        if (!signature.isValid(sig, body, Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid-signature", "Callback signature is invalid or expired");
        }
        JsonNode s = json.readTree(body);
        Urgency urgency;
        try {
            urgency = Urgency.valueOf(s.path("urgency").asText());
        } catch (IllegalArgumentException e) {
            log.warn("Model returned an invalid urgency '{}'; case stays on rules", s.path("urgency").asText());
            return Map.of("result", "INVALID_RESULT");
        }
        Optional<UUID> caseId = vault.resolve(s.path("caseToken").asText());
        if (caseId.isEmpty()) {
            return Map.of("result", "UNKNOWN_TOKEN");
        }
        List<String> factors = new ArrayList<>();
        s.path("contributingFactors").forEach(f -> factors.add(f.asText()));
        TriageCases.SuggestionOutcome outcome = cases.applySuggestion(caseId.get(), urgency,
                new BigDecimal(s.path("confidence").asText("0")), factors, s.path("modelVersion").asText(),
                s.path("mode").asText("ACTIVE"));
        return Map.of("result", outcome.name());
    }
}
