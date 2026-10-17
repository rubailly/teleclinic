package rw.teleclinic.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HmacSignatureTests {

    private final HmacSignature signature = new HmacSignature("shared-secret", Duration.ofMinutes(5));
    private final Instant now = Instant.parse("2026-10-16T08:00:00Z");
    private final String body = "{\"eventId\":\"evt_1\",\"type\":\"payment.succeeded\",\"paymentId\":\"pay_1\"}";

    @Test
    void accepts_a_correct_signature() {
        assertThat(signature.isValid(signature.sign(body, now), body, now.plusSeconds(30))).isTrue();
    }

    @Test
    void rejects_a_changed_body() {
        String header = signature.sign(body, now);
        assertThat(signature.isValid(header, body.replace("pay_1", "pay_2"), now)).isFalse();
    }

    @Test
    void rejects_another_secret() {
        String forged = new HmacSignature("not-the-secret", Duration.ofMinutes(5)).sign(body, now);
        assertThat(signature.isValid(forged, body, now)).isFalse();
    }

    @Test
    void rejects_a_replay_outside_the_time_window() {
        String header = signature.sign(body, now);
        assertThat(signature.isValid(header, body, now.plus(Duration.ofMinutes(6)))).isFalse();
    }

    @Test
    void rejects_missing_or_malformed_headers() {
        assertThat(signature.isValid(null, body, now)).isFalse();
        assertThat(signature.isValid("garbage", body, now)).isFalse();
        assertThat(signature.isValid("t=abc,v1=00", body, now)).isFalse();
        assertThat(signature.isValid("t=" + now.getEpochSecond(), body, now)).isFalse();
    }
}
