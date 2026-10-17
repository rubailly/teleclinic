package rw.teleclinic.core.triage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import rw.teleclinic.core.shared.ApiException;

/**
 * Talks to the model service in the AI trust zone: which model is active, and the kill switch.
 * If the service cannot be reached the platform behaves as rules-only (fail safe).
 */
@Component
class ModelControl {

    record ModelInfo(String modelVersion, String mode, String trainedOn, Map<String, Object> validation,
                     String modelCardUrl, String reason) {
        static ModelInfo unreachable() {
            return new ModelInfo(null, "UNREACHABLE", null, Map.of(), null, "model service not reachable");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ModelControl.class);
    private static final Duration CACHE = Duration.ofSeconds(3);

    private final RestClient http;
    private volatile ModelInfo cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    ModelControl(TriageProperties props, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(1));
        timeouts.setReadTimeout(Duration.ofSeconds(2));
        this.http = builder.clone().baseUrl(props.modelServiceUrl()).requestFactory(timeouts).build();
    }

    ModelInfo active() {
        if (cached != null && cachedAt.plus(CACHE).isAfter(Instant.now())) {
            return cached;
        }
        ModelInfo info;
        try {
            info = http.get().uri("/models/active").retrieve().body(ModelInfo.class);
        } catch (RestClientException e) {
            log.warn("Model service unreachable: {}", e.getMessage());
            info = ModelInfo.unreachable();
        }
        cached = info;
        cachedAt = Instant.now();
        return info;
    }

    boolean isDisabled() {
        return "DISABLED".equals(active().mode());
    }

    ModelInfo setMode(String mode, String reason) {
        try {
            String path = "DISABLED".equals(mode) ? "/models/kill-switch" : "/models/activate";
            ModelInfo info = http.post().uri(path).body(Map.of("reason", reason)).retrieve().body(ModelInfo.class);
            cached = info;
            cachedAt = Instant.now();
            return info;
        } catch (RestClientException e) {
            throw ApiException.unavailable("model-unavailable", "Model service not reachable: " + e.getMessage());
        }
    }
}
