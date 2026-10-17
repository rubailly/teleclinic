package rw.teleclinic.core.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import rw.teleclinic.core.IntegrationTest;
import rw.teleclinic.core.consent.ConsentService;
import rw.teleclinic.core.consultation.ConsultationService;
import rw.teleclinic.core.consultation.ConsultationView;
import rw.teleclinic.core.shared.HmacSignature;

@RecordApplicationEvents
class HumanInTheLoopTests extends IntegrationTest {

    static final String CLINICIAN = "00000000-0000-4000-a000-000000000101";

    @MockBean
    ModelControl models;
    @MockBean
    PseudonymVault vault;

    @Autowired
    MockMvc mvc;
    @Autowired
    TriageCases cases;
    @Autowired
    ConsultationService consultations;
    @Autowired
    ConsentService consents;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ApplicationEvents events;

    final UUID keza = UUID.fromString(KEZA_PATIENT_ID);

    @BeforeEach
    void setUp() {
        consents.give(UWASE, keza, ConsentService.DEFAULT_PURPOSES);
        when(models.isDisabled()).thenReturn(false);
        when(models.active()).thenReturn(new ModelControl.ModelInfo("tc-triage-test", "ACTIVE", null, Map.of(), null, null));
    }

    /** Keza: fever and fast breathing, rules say URGENT. Queued as if paid. */
    private UUID queuedCase() {
        ConsultationView c = consultations.book(UWASE, new ConsultationService.BookingRequest(keza, "USSD",
                List.of("fever", "fast_breathing"), 2, false, new TriageIntake.Vitals(39.4, null, null, null)), null);
        cases.markQueued(c.id());
        return c.id();
    }

    @Test
    void the_model_can_raise_urgency_and_the_case_becomes_ai_proposed() {
        UUID id = queuedCase();
        assertThat(cases.view(id).status()).isEqualTo("RULES_ONLY");
        assertThat(events.stream(TriageRequested.class)).anySatisfy(e -> assertThat(e.caseId()).isEqualTo(id));

        cases.applySuggestion(id, Urgency.EMERGENCY, new BigDecimal("0.81"), List.of("fast_breathing", "age_5_11"), "tc-triage-test", "ACTIVE");

        TriageCaseView v = cases.view(id);
        assertThat(v.status()).isEqualTo("AI_PROPOSED");
        assertThat(v.rulesUrgency()).isEqualTo("URGENT");
        assertThat(v.modelUrgency()).isEqualTo("EMERGENCY");
        assertThat(v.mergedUrgency()).isEqualTo("EMERGENCY");
    }

    @Test
    void the_model_can_never_lower_the_rules_floor() {
        UUID id = queuedCase();
        cases.applySuggestion(id, Urgency.ROUTINE, new BigDecimal("0.92"), List.of(), "tc-triage-test", "ACTIVE");
        TriageCaseView v = cases.view(id);
        assertThat(v.modelUrgency()).isEqualTo("ROUTINE");
        assertThat(v.mergedUrgency()).isEqualTo("URGENT");
    }

    @Test
    void orders_are_refused_until_a_clinician_finalises_the_triage() throws Exception {
        UUID id = queuedCase();
        cases.applySuggestion(id, Urgency.URGENT, new BigDecimal("0.7"), List.of(), "tc-triage-test", "ACTIVE");
        String order = "{\"kind\":\"PRESCRIPTION\",\"detail\":\"illustrative order\"}";

        mvc.perform(post("/api/clinician/consultations/" + id + "/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(order).with(signedInAs(CLINICIAN, "clinician1", "clinician")).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.issue[0].code").value("triage-not-final"));

        mvc.perform(post("/triage/v1/cases/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"OVERRIDE\",\"finalUrgency\":\"EMERGENCY\",\"reason\":\"cyanosis reported on the call\"}")
                        .with(signedInAs(CLINICIAN, "clinician1", "clinician")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.finalUrgency").value("EMERGENCY"));

        mvc.perform(post("/api/clinician/consultations/" + id + "/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(order).with(signedInAs(CLINICIAN, "clinician1", "clinician")).with(csrf()))
                .andExpect(status().isCreated());

        assertThat(cases.provenance(id)).extracting(p -> p.get("activity")).containsExactly("AI_SUGGESTION", "CLINICIAN_REVIEW");
        assertThat(cases.view(id).history()).extracting(TriageCaseView.Transition::to)
                .containsSubsequence("RULES_ONLY", "AI_PROPOSED", "UNDER_REVIEW", "OVERRIDDEN", "FINAL");
    }

    @Test
    void an_override_needs_a_reason_and_a_confirmation_cannot_change_urgency() throws Exception {
        UUID id = queuedCase();
        mvc.perform(post("/triage/v1/cases/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"OVERRIDE\",\"finalUrgency\":\"EMERGENCY\"}")
                        .with(signedInAs(CLINICIAN, "clinician1", "clinician")).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/triage/v1/cases/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM\",\"finalUrgency\":\"ROUTINE\"}")
                        .with(signedInAs(CLINICIAN, "clinician1", "clinician")).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(cases.isFinal(id)).isFalse();
    }

    @Test
    void only_clinicians_review_and_a_final_case_cannot_be_reviewed_again() throws Exception {
        UUID id = queuedCase();
        String confirm = "{\"decision\":\"CONFIRM\"}";
        mvc.perform(post("/triage/v1/cases/" + id + "/review").contentType(MediaType.APPLICATION_JSON).content(confirm)
                        .with(signedInAs(UWASE, "patient1", "patient")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/triage/v1/cases/" + id + "/review").contentType(MediaType.APPLICATION_JSON).content(confirm)
                        .with(signedInAs(CLINICIAN, "clinician1", "clinician")).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/triage/v1/cases/" + id + "/review").contentType(MediaType.APPLICATION_JSON).content(confirm)
                        .with(signedInAs(CLINICIAN, "clinician1", "clinician")).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void the_queue_shows_the_case_and_the_model_status() throws Exception {
        UUID id = queuedCase();
        mvc.perform(get("/api/clinician/queue").with(signedInAs(CLINICIAN, "clinician1", "clinician")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesOnlyMode").value(false))
                .andExpect(jsonPath("$.cases[?(@.consultationId == '" + id + "')].status").value("RULES_ONLY"));
        mvc.perform(get("/api/clinician/queue").with(signedInAs(UWASE, "patient1", "patient")))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_signed_callback_is_applied_and_an_unsigned_one_is_refused() throws Exception {
        UUID id = queuedCase();
        when(vault.resolve(anyString())).thenReturn(Optional.of(id));
        String body = "{\"caseToken\":\"tok_1\",\"urgency\":\"EMERGENCY\",\"confidence\":0.66,"
                + "\"contributingFactors\":[\"fast_breathing\"],\"modelVersion\":\"tc-triage-test\",\"mode\":\"ACTIVE\"}";
        String sig = new HmacSignature("test-callback-secret", Duration.ofMinutes(5)).sign(body, Instant.now());

        mvc.perform(post("/internal/triage/suggestions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/triage/suggestions").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Signature", sig))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PROPOSED"));
        assertThat(cases.view(id).mergedUrgency()).isEqualTo("EMERGENCY");
    }

    @Test
    void shadow_mode_suggestions_are_recorded_but_change_nothing() {
        UUID id = queuedCase();
        assertThat(cases.applySuggestion(id, Urgency.EMERGENCY, new BigDecimal("0.9"), List.of(), "tc-triage-next", "SHADOW"))
                .isEqualTo(TriageCases.SuggestionOutcome.SHADOW);
        TriageCaseView v = cases.view(id);
        assertThat(v.status()).isEqualTo("RULES_ONLY");
        assertThat(v.mergedUrgency()).isEqualTo("URGENT");
        assertThat(cases.provenance(id)).hasSize(1);
    }

    @Test
    void an_unreviewed_case_past_its_deadline_escalates_and_keeps_the_rules_urgency() {
        UUID id = queuedCase();
        cases.applySuggestion(id, Urgency.URGENT, new BigDecimal("0.7"), List.of(), "tc-triage-test", "ACTIVE");
        jdbc.sql("UPDATE triage.triage_case SET sla_due_at = now() - interval '1 minute' WHERE consultation_id = ?").param(id).update();
        cases.escalateOverdue();
        TriageCaseView v = cases.view(id);
        assertThat(v.status()).isEqualTo("UNREVIEWED_TIMEOUT");
        assertThat(v.mergedUrgency()).isEqualTo("URGENT");
    }

    @Test
    void with_the_kill_switch_on_no_case_is_sent_to_the_model() {
        when(models.isDisabled()).thenReturn(true);
        UUID id = queuedCase();
        assertThat(events.stream(TriageRequested.class).filter(e -> e.caseId().equals(id))).isEmpty();
        assertThat(cases.view(id).status()).isEqualTo("RULES_ONLY");
    }
}
