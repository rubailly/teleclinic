package rw.teleclinic.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.web.servlet.MockMvc;
import rw.teleclinic.core.IntegrationTest;

// Spring Boot turns metrics export off in tests unless asked
@AutoConfigureObservability
class MetricsEndpointTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void prometheusServesTheServiceMetricsWithoutASession() throws Exception {
        String body = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .contains("teleclinic_triage_rules_seconds_bucket")
                .contains("teleclinic_triage_awaiting_review")
                .contains("teleclinic_outbox_pending");
        // Counts and timings only: no national ID, phone number or name in any label
        assertThat(body).doesNotContainPattern("(?<![\\d.])1\\d{15}(?!\\d)").doesNotContainPattern("\\+2507\\d{8}").doesNotContain("Uwase");
    }
}
