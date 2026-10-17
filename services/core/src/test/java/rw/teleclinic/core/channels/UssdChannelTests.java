package rw.teleclinic.core.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import rw.teleclinic.core.IntegrationTest;

class UssdChannelTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper json;

    private ResultActions call(String path, String body, String key) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).header("X-Gateway-Key", key));
    }

    private JsonNode ok(String path, String body) throws Exception {
        String out = call(path, body, "test-gateway-key").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }

    @Test
    void only_the_gateway_may_call() throws Exception {
        call("/internal/ussd/unlock", "{\"msisdn\":\"+250788000001\",\"pin\":\"1234\"}", "wrong").andExpect(status().isUnauthorized());
    }

    @Test
    void a_mother_unlocks_with_her_pin_and_books_for_her_daughter_in_the_session() throws Exception {
        JsonNode session = ok("/internal/ussd/unlock", "{\"msisdn\":\"+250788000001\",\"pin\":\"1234\"}");
        String token = session.path("token").asText();
        assertThat(token).startsWith("ussd_");
        assertThat(session.path("people")).hasSize(2);
        JsonNode keza = session.path("people").get(1);
        assertThat(keza.path("relation").asText()).isEqualTo("GUARDIAN");
        assertThat(keza.path("firstName").asText()).isEqualTo("Keza");

        ok("/internal/ussd/consent", "{\"token\":\"" + token + "\",\"patientId\":\"" + KEZA_PATIENT_ID + "\",\"aiSupport\":true}");
        JsonNode booked = ok("/internal/ussd/book", "{\"token\":\"" + token + "\",\"patientId\":\"" + KEZA_PATIENT_ID
                + "\",\"symptoms\":[\"fever\",\"fast_breathing\"],\"durationDays\":2}");
        assertThat(booked.path("urgency").asText()).isEqualTo("URGENT");
        assertThat(booked.path("advice").asText()).hasSizeLessThanOrEqualTo(160);
    }

    @Test
    void three_wrong_pins_lock_the_phone_link() throws Exception {
        jdbc.sql("INSERT INTO identity.phone_link (msisdn, subject, pin_hash) VALUES ('+250788000099', 'subject-x', crypt('4321', gen_salt('bf', 4)))")
                .update();
        String wrong = "{\"msisdn\":\"+250788000099\",\"pin\":\"0000\"}";
        call("/internal/ussd/unlock", wrong, "test-gateway-key").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.issue[0].code").value("wrong-pin"));
        call("/internal/ussd/unlock", wrong, "test-gateway-key").andExpect(status().isUnauthorized());
        call("/internal/ussd/unlock", wrong, "test-gateway-key").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.issue[0].code").value("locked"));
        // Locked even with the right PIN
        call("/internal/ussd/unlock", "{\"msisdn\":\"+250788000099\",\"pin\":\"4321\"}", "test-gateway-key")
                .andExpect(status().isLocked());
    }

    @Test
    void an_unknown_or_expired_token_is_refused() throws Exception {
        call("/internal/ussd/book", "{\"token\":\"ussd_nope\",\"patientId\":\"" + KEZA_PATIENT_ID + "\",\"symptoms\":[\"fever\"]}",
                "test-gateway-key").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.issue[0].code").value("session-expired"));
    }
}
