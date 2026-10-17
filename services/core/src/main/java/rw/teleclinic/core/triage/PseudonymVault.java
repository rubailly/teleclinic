package rw.teleclinic.core.triage;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Re-identification happens only here, in the core, after inference (Section 4.7.2, layer 4). The model only
 * ever sees a random token; the anonymization gateway keeps the token map and releases it to the core alone.
 */
@Component
class PseudonymVault {

    private final RestClient http;

    PseudonymVault(TriageProperties props, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(1));
        timeouts.setReadTimeout(Duration.ofSeconds(2));
        this.http = builder.clone().baseUrl(props.anonymizerUrl()).requestFactory(timeouts)
                .defaultHeader("X-Vault-Key", props.vaultKey()).build();
    }

    @SuppressWarnings("unchecked")
    Optional<UUID> resolve(String token) {
        try {
            Map<String, Object> entry = http.get().uri("/vault/{token}", token).retrieve().body(Map.class);
            return Optional.ofNullable(entry).map(e -> UUID.fromString((String) e.get("caseId")));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
