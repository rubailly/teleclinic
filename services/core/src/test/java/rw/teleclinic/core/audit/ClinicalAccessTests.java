package rw.teleclinic.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import rw.teleclinic.core.IntegrationTest;

/** Scene 5b: purpose of use and consent on every clinical read, and the patient's "who accessed my record". */
class ClinicalAccessTests extends IntegrationTest {

    static final String DOCTOR = "00000000-0000-4000-a000-000000000003";
    static final String ERIC_PATIENT_ID = "10000000-0000-4000-b000-000000000002";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private Map<String, Object> lastFor(String patientId) {
        return jdbc.sql("SELECT * FROM audit.event WHERE patient_id = ?::uuid ORDER BY seq DESC LIMIT 1")
                .param(patientId).query().singleRow();
    }

    @Test
    void a_clinical_read_without_a_purpose_is_refused_and_recorded() throws Exception {
        mvc.perform(get("/fhir/Patient/" + ERIC_PATIENT_ID).with(signedInAs(DOCTOR, "clinician1", "clinician")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].code").value("purpose-of-use-required"));
        Map<String, Object> e = lastFor(ERIC_PATIENT_ID);
        assertThat(e.get("outcome")).isEqualTo("DENIED");
        assertThat(e.get("reason")).isEqualTo("purpose-of-use-required");
        assertThat(e.get("actor")).isEqualTo(DOCTOR);
    }

    @Test
    void treatment_needs_the_patients_consent_then_the_read_is_recorded_with_its_basis() throws Exception {
        String keza = KEZA_PATIENT_ID;
        jdbc.sql("UPDATE consent.consent SET status = 'WITHDRAWN' WHERE patient_id = ?::uuid").param(keza).update();
        mvc.perform(get("/fhir/Patient/" + keza).header("X-Purpose-Of-Use", "TREAT")
                        .with(signedInAs(DOCTOR, "clinician1", "clinician")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.issue[0].code").value("consent-required"));

        mvc.perform(post("/api/patients/" + keza + "/consents").with(signedInAs(UWASE, "patient1", "patient")).with(csrf()))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/fhir/Patient/" + keza).header("X-Purpose-Of-Use", "TREAT")
                        .with(signedInAs(DOCTOR, "clinician1", "clinician")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Patient"))
                .andExpect(jsonPath("$.identifier[0].value").value("180514-0000-0001"));
        Map<String, Object> e = lastFor(keza);
        assertThat(e.get("outcome")).isEqualTo("SUCCESS");
        assertThat(e.get("purpose")).isEqualTo("TREAT");
        assertThat((String) e.get("consent_basis")).startsWith("consent:");
    }

    @Test
    void a_patient_may_not_claim_a_clinical_purpose_or_read_someone_else() throws Exception {
        mvc.perform(get("/fhir/Patient/" + ERIC_PATIENT_ID).header("X-Purpose-Of-Use", "TREAT")
                        .with(signedInAs(UWASE, "patient1", "patient")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.issue[0].code").value("purpose-not-allowed"));
        mvc.perform(get("/fhir/Patient/" + ERIC_PATIENT_ID).header("X-Purpose-Of-Use", "PATIENT_ACCESS")
                        .with(signedInAs(UWASE, "patient1", "patient")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.issue[0].code").value("not-your-record"));
        mvc.perform(get("/fhir/Patient/" + KEZA_PATIENT_ID).header("X-Purpose-Of-Use", "PATIENT_ACCESS")
                        .with(signedInAs(UWASE, "patient1", "patient")))
                .andExpect(status().isOk());
    }

    @Test
    void break_glass_needs_a_reason_and_is_then_allowed_without_consent() throws Exception {
        jdbc.sql("UPDATE consent.consent SET status = 'WITHDRAWN' WHERE patient_id = ?::uuid").param(ERIC_PATIENT_ID).update();
        mvc.perform(get("/fhir/Patient/" + ERIC_PATIENT_ID).header("X-Purpose-Of-Use", "BREAK_GLASS")
                        .with(signedInAs(DOCTOR, "clinician1", "clinician")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.issue[0].code").value("break-glass-reason-required"));
        mvc.perform(get("/fhir/Patient/" + ERIC_PATIENT_ID).header("X-Purpose-Of-Use", "BREAK_GLASS")
                        .header("X-Break-Glass-Reason", "unconscious on arrival, no consent possible")
                        .with(signedInAs(DOCTOR, "clinician1", "clinician")))
                .andExpect(status().isOk());
        Map<String, Object> e = lastFor(ERIC_PATIENT_ID);
        assertThat(e.get("consent_basis")).isEqualTo("break-glass");
        assertThat(e.get("reason")).isEqualTo("unconscious on arrival, no consent possible");
    }

    @Test
    void the_patient_sees_who_read_their_record_and_why() throws Exception {
        mvc.perform(get("/fhir/Patient/" + ERIC_PATIENT_ID).header("X-Purpose-Of-Use", "BREAK_GLASS")
                        .header("X-Break-Glass-Reason", "emergency")
                        .with(signedInAs(DOCTOR, "clinician1", "clinician")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/patients/" + ERIC_PATIENT_ID + "/access-log").with(signedInAs(ERIC, "patient2", "patient")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].role").value("clinician"))
                .andExpect(jsonPath("$.entries[0].purpose").value("BREAK_GLASS"))
                .andExpect(jsonPath("$.entries[0].breakGlassReason").value("emergency"))
                .andExpect(jsonPath("$.entries[0].you").value(false));
        // Someone else's access log is not visible
        mvc.perform(get("/api/me/patients/" + ERIC_PATIENT_ID + "/access-log").with(signedInAs(UWASE, "patient1", "patient")))
                .andExpect(status().isForbidden());
    }

    @Test
    void the_trail_and_its_check_are_for_auditors_only() throws Exception {
        mvc.perform(get("/api/audit/verify").with(signedInAs(DOCTOR, "clinician1", "clinician"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/verify").with(signedInAs("00000000-0000-4000-a000-000000000005", "admin1", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INTACT"));
    }
}
