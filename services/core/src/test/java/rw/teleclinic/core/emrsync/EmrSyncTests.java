package rw.teleclinic.core.emrsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import rw.teleclinic.core.IntegrationTest;
import rw.teleclinic.core.triage.TriageFinalised;

/** Scenes 3b and 3c: no echo loops, EMR-authoritative contact details, durable retries, and identity bands. */
class EmrSyncTests extends IntegrationTest {

    static final String KEY = "test-sync-key";
    static final String CLERK = "00000000-0000-4000-a000-000000000004";

    @Autowired
    MockMvc mvc;

    @Autowired
    SyncQueue queue;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper json;

    /** A FHIR Patient as OpenMRS's FHIR2 module returns it. */
    private JsonNode emrPatient(String id, String given, String family, String gender, String birth, String phone,
                                Map<String, String> identifiers) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("resourceType", "Patient");
        p.put("id", id);
        p.put("meta", Map.of("lastUpdated", Instant.now().toString()));
        p.put("name", List.of(Map.of("given", List.of(given), "family", family)));
        p.put("gender", gender);
        p.put("birthDate", birth);
        p.put("telecom", phone == null ? List.of() : List.of(Map.of("value", phone)));
        p.put("identifier", identifiers.entrySet().stream()
                .map(e -> Map.of("type", Map.of("text", e.getKey()), "value", e.getValue())).toList());
        return json.valueToTree(p);
    }

    private org.springframework.test.web.servlet.ResultActions inbound(JsonNode patient) throws Exception {
        return mvc.perform(post("/sync/inbound").header("X-Sync-Key", KEY).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("externalSystem", "openmrs-demo-facility", "resource", patient,
                        "origin", "openmrs", "contentHash", "x"))));
    }

    @Test
    void our_own_write_comes_back_as_an_echo_and_a_phone_edited_in_the_emr_is_applied_once() throws Exception {
        UUID keza = UUID.fromString(KEZA_PATIENT_ID);
        mvc.perform(post("/api/patients/" + keza + "/consents").with(signedInAs(UWASE, "patient1", "patient")).with(csrf()))
                .andExpect(status().is2xxSuccessful());
        UUID consultation = UUID.randomUUID();
        assertThat(queue.enqueueEncounter(new TriageFinalised(consultation, keza, "USSD", "URGENT", "CONFIRM", null,
                "Dr Mukamana", Instant.now()))).isTrue();
        assertThat(queue.enqueueEncounter(new TriageFinalised(consultation, keza, "USSD", "URGENT", "CONFIRM", null,
                "Dr Mukamana", Instant.now()))).as("same consultation queued once").isFalse();
        SyncQueue.Item item = queue.due(50).stream().filter(i -> i.reference().equals(consultation.toString())).findFirst().orElseThrow();
        assertThat(item.payload().path("patient").path("upi").asText()).isEqualTo("180514-0000-0001");

        // The gateway wrote it; the EMR record as it now reads becomes the ledger's hash
        JsonNode written = emrPatient("emr-keza", "Keza", "Demo", "female", "2018-05-14", null,
                Map.of("TeleClinic ID", keza.toString(), "UPI", "180514-0000-0001"));
        queue.done(item.id(), "CREATED", written, "emr-encounter-1");
        assertThat(queue.due(50)).noneMatch(i -> i.id().equals(item.id()));

        inbound(written).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ECHO_DROPPED"));

        JsonNode edited = emrPatient("emr-keza", "Keza", "Demo", "female", "2018-05-14", "+250788000777",
                Map.of("TeleClinic ID", keza.toString(), "UPI", "180514-0000-0001"));
        inbound(edited).andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertThat(jdbc.sql("SELECT phone FROM patient_index.patient WHERE id = ?").param(keza).query(String.class).single())
                .isEqualTo("+250788000777");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.event WHERE patient_id = ? AND actor = 'openmrs-sync' AND action = 'UPDATE'")
                .param(keza).query(Long.class).single()).isEqualTo(1);
        // The same change seen again (the next poll overlaps) is an echo, and nothing goes back out
        inbound(edited).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ECHO_DROPPED"));
        assertThat(queue.due(50)).noneMatch(i -> i.patientId().equals(keza));
    }

    @Test
    void a_write_that_keeps_failing_backs_off_then_becomes_a_dead_letter_that_can_be_replayed() {
        UUID ref = UUID.randomUUID();
        queue.enqueueIdentifiers("ADD_IDENTIFIER", ref, UUID.fromString(UWASE_PATIENT_ID), "emr-x", Map.of("teleclinicId", "x"));
        UUID id = queue.due(100).stream().filter(i -> i.reference().equals(ref.toString())).findFirst().orElseThrow().id();
        queue.failed(id, "connection refused");
        assertThat(queue.due(100)).as("waiting out its backoff").noneMatch(i -> i.id().equals(id));
        jdbc.sql("UPDATE sync.outbound SET attempts = 9, next_attempt_at = now() WHERE id = ?").param(id).update();
        queue.failed(id, "connection refused");
        assertThat(jdbc.sql("SELECT status FROM sync.outbound WHERE id = ?").param(id).query(String.class).single()).isEqualTo("DEAD");
        assertThat(queue.replay()).isGreaterThanOrEqualTo(1);
        assertThat(queue.due(100)).anyMatch(i -> i.id().equals(id));
    }

    @Test
    void facility_records_land_in_the_right_band_and_a_clerk_can_link_and_unlink() throws Exception {
        inbound(emrPatient("emr-marie", "Uwimana", "Marie", "female", "1990-03-04", null, Map.of("OpenMRS Identification Number", "F-1001")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("REVIEW"));
        inbound(emrPatient("emr-jean", "Jean", "Habimana", "male", "1985-06-10", null, Map.of("National ID (NIDA)", "0000198500000077")))
                .andExpect(jsonPath("$.outcome").value("BLOCKED"));
        inbound(emrPatient("emr-aline", "Aline", "Mukeshimana", "female", "2016-09-21", null, Map.of("UPI", "160921-0000-0042")))
                .andExpect(jsonPath("$.outcome").value("AUTO_LINKED"))
                .andExpect(jsonPath("$.teleclinicPatient").value("Patient/10000000-0000-4000-b000-000000000013"));

        mvc.perform(get("/identity/review-queue").with(signedInAs(CLERK, "clerk1", "clerk")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.right.id == 'emr-marie')].left.id").value("10000000-0000-4000-b000-000000000011"));
        mvc.perform(get("/identity/review-queue").with(signedInAs(UWASE, "patient1", "patient"))).andExpect(status().isForbidden());
        mvc.perform(get("/identity/blocked").with(signedInAs(CLERK, "clerk1", "clerk")))
                .andExpect(jsonPath("$[?(@.right.id == 'emr-jean')]").exists());

        String body = json.writeValueAsString(Map.of("sourcePatient", "Patient/10000000-0000-4000-b000-000000000011",
                "targetPatient", "Patient/emr-marie", "linkType", "seealso", "decidedBy", "clerk1",
                "reason", "same mother's name and village confirmed by phone"));
        String created = mvc.perform(post("/identity/links").with(signedInAs(CLERK, "clerk1", "clerk")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String linkId = json.readTree(created).path("id").asText();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.event WHERE action = 'LINK' AND actor = ?").param(CLERK)
                .query(Long.class).single()).isEqualTo(1);
        // The EMR record now resolves to Marie; the national ID write-back is the gateway's next job
        inbound(emrPatient("emr-marie", "Uwimana", "Marie", "female", "1990-03-04", null, Map.of("OpenMRS Identification Number", "F-1001")))
                .andExpect(jsonPath("$.teleclinicPatient").value("Patient/10000000-0000-4000-b000-000000000011"));

        mvc.perform(delete("/identity/links/" + linkId).param("reason", "a second visit showed two different women")
                        .with(signedInAs(CLERK, "clerk1", "clerk")).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.event WHERE action = 'UNLINK'").query(Long.class).single()).isEqualTo(1);
        mvc.perform(get("/identity/review-queue").with(signedInAs(CLERK, "clerk1", "clerk")))
                .andExpect(jsonPath("$[?(@.right.id == 'emr-marie')]").exists());
    }

    @Test
    void only_the_gateway_may_call_the_sync_endpoints() throws Exception {
        mvc.perform(get("/internal/sync/outbound/due")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/sync/outbound/due").header("X-Sync-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/sync/outbound/due").header("X-Sync-Key", KEY)).andExpect(status().isOk());
    }
}
