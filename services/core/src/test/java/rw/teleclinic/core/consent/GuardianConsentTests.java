package rw.teleclinic.core.consent;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import rw.teleclinic.core.IntegrationTest;

class GuardianConsentTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void a_mother_sees_herself_and_her_daughter_as_people_she_can_book_for() throws Exception {
        mvc.perform(get("/api/me/patients").with(signedInAs(UWASE, "patient1", "patient")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relation").value("SELF"))
                .andExpect(jsonPath("$[0].name").value("Uwase Demo"))
                .andExpect(jsonPath("$[1].relation").value("GUARDIAN"))
                .andExpect(jsonPath("$[1].relationship").value("MTH"))
                .andExpect(jsonPath("$[1].ageBand").value("5-11"));
    }

    @Test
    void the_guardian_gives_consent_for_the_child_and_is_recorded_as_performer() throws Exception {
        mvc.perform(post("/api/patients/" + KEZA_PATIENT_ID + "/consents")
                        .with(signedInAs(UWASE, "patient1", "patient")).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.performerType").value("GUARDIAN"))
                .andExpect(jsonPath("$.performerSubject").value(UWASE))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void someone_else_cannot_consent_for_the_child() throws Exception {
        mvc.perform(post("/api/patients/" + KEZA_PATIENT_ID + "/consents")
                        .with(signedInAs(ERIC, "patient2", "patient")).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].code").value("forbidden"));
    }

    @Test
    void consent_without_a_csrf_token_is_refused() throws Exception {
        mvc.perform(post("/api/patients/" + UWASE_PATIENT_ID + "/consents")
                        .with(signedInAs(UWASE, "patient1", "patient")))
                .andExpect(status().isForbidden());
    }
}
