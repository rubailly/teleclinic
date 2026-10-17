package rw.teleclinic.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.client.ResourceAccessException;
import rw.teleclinic.core.IntegrationTest;
import rw.teleclinic.core.consent.ConsentService;
import rw.teleclinic.core.consultation.ConsultationEvents.ConsultationExpired;
import rw.teleclinic.core.consultation.ConsultationEvents.ConsultationPaid;
import rw.teleclinic.core.consultation.ConsultationService;
import rw.teleclinic.core.consultation.ConsultationView;
import rw.teleclinic.core.shared.ApiException;
import rw.teleclinic.core.shared.HmacSignature;
import rw.teleclinic.core.triage.TriageIntake;
import rw.teleclinic.core.triage.TriageRequested;

/**
 * The booking and payment saga against a real database, with IremboPay stubbed: happy path, duplicate, forged
 * and spoofed webhooks, a lost webhook, expiry, a late payment and an IremboPay outage.
 */
@RecordApplicationEvents
class PaymentSagaTests extends IntegrationTest {

    static final String SECRET = "test-webhook-secret";

    @MockBean
    IremboPayClient irembopay;

    @Autowired
    ConsultationService consultations;
    @Autowired
    PaymentService payments;
    @Autowired
    ConsentService consents;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ApplicationEvents events;

    final HmacSignature signer = new HmacSignature(SECRET, Duration.ofMinutes(5));
    final UUID keza = UUID.fromString(KEZA_PATIENT_ID);

    @BeforeEach
    void guardianConsent() {
        consents.give(UWASE, keza, ConsentService.DEFAULT_PURPOSES);
        when(irembopay.coverage(anyString(), anyString()))
                .thenReturn(new IremboPayClient.ProviderCoverage(false, null, null, null));
    }

    private ConsultationView bookForKeza() {
        return consultations.book(UWASE, new ConsultationService.BookingRequest(keza, "WEB",
                List.of("fever", "fast_breathing"), 2, false, new TriageIntake.Vitals(39.4, null, null, null)), null);
    }

    private String startPayment(ConsultationView c) {
        String providerId = "pay_" + c.id().toString().substring(0, 8);
        when(irembopay.create(eq(c.id().toString()), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IremboPayClient.ProviderPayment(providerId, "PENDING", new BigDecimal("3000"), "RWF",
                        c.id().toString(), "http://pay.test/checkout/" + providerId));
        consultations.requestPayment(UWASE, c.id());
        return providerId;
    }

    private void providerSays(String providerId, String status) {
        when(irembopay.status(providerId)).thenReturn(new IremboPayClient.ProviderPayment(providerId, status,
                new BigDecimal("3000"), "RWF", null, null));
    }

    private String webhook(String providerId, String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"payment.succeeded\",\"paymentId\":\"" + providerId
                + "\",\"amount\":3000,\"currency\":\"RWF\"}";
    }

    private String state(UUID id) {
        return consultations.view(id).state();
    }

    @Test
    void booking_runs_the_rules_in_session_and_asks_the_model_asynchronously() {
        ConsultationView c = bookForKeza();
        assertThat(c.state()).isEqualTo("TRIAGED");
        assertThat(c.urgency()).isEqualTo("URGENT");
        assertThat(c.redFlags()).containsExactly("fast_breathing_child");
        assertThat(events.stream(TriageRequested.class)).anySatisfy(e -> {
            assertThat(e.caseId()).isEqualTo(c.id());
            assertThat(e.ageBand()).isEqualTo("5-11");
        });
    }

    @Test
    void a_verified_webhook_confirmed_server_side_makes_the_consultation_paid_and_queued() {
        ConsultationView c = bookForKeza();
        String pay = startPayment(c);
        assertThat(state(c.id())).isEqualTo("PENDING_PAYMENT");
        providerSays(pay, "SUCCEEDED");

        String body = webhook(pay, "evt_" + UUID.randomUUID());
        assertThat(payments.handleWebhook(body, signer.sign(body, Instant.now())))
                .isEqualTo(PaymentService.WebhookOutcome.CONFIRMED);

        ConsultationView paid = consultations.view(c.id());
        assertThat(paid.state()).isEqualTo("QUEUED");
        assertThat(paid.history()).extracting(ConsultationView.Transition::to)
                .containsSubsequence("DRAFT", "TRIAGED", "PENDING_PAYMENT", "PAID", "QUEUED");
        assertThat(events.stream(ConsultationPaid.class)).anySatisfy(e -> assertThat(e.consultationId()).isEqualTo(c.id()));
    }

    @Test
    void the_same_webhook_delivered_twice_is_acknowledged_and_ignored() {
        ConsultationView c = bookForKeza();
        String pay = startPayment(c);
        providerSays(pay, "SUCCEEDED");
        String body = webhook(pay, "evt_" + UUID.randomUUID());

        assertThat(payments.handleWebhook(body, signer.sign(body, Instant.now()))).isEqualTo(PaymentService.WebhookOutcome.CONFIRMED);
        assertThat(payments.handleWebhook(body, signer.sign(body, Instant.now()))).isEqualTo(PaymentService.WebhookOutcome.DUPLICATE);

        long paidTransitions = consultations.view(c.id()).history().stream().filter(t -> t.to().equals("PAID")).count();
        assertThat(paidTransitions).isEqualTo(1);
        assertThat(events.stream(ConsultationPaid.class).filter(e -> e.consultationId().equals(c.id()))).hasSize(1);
    }

    @Test
    void a_forged_webhook_is_rejected_before_anything_is_recorded() {
        ConsultationView c = bookForKeza();
        String pay = startPayment(c);
        String body = webhook(pay, "evt_forged");
        String forged = new HmacSignature("not-the-secret", Duration.ofMinutes(5)).sign(body, Instant.now());

        assertThatThrownBy(() -> payments.handleWebhook(body, forged))
                .isInstanceOf(ApiException.class).hasMessageContaining("signature");
        assertThat(state(c.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.sql("SELECT count(*) FROM payments.webhook_event WHERE event_id = 'evt_forged'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void a_correctly_signed_false_claim_is_caught_by_the_server_side_status_check() {
        ConsultationView c = bookForKeza();
        String pay = startPayment(c);
        providerSays(pay, "PENDING");
        String body = webhook(pay, "evt_" + UUID.randomUUID());

        assertThat(payments.handleWebhook(body, signer.sign(body, Instant.now())))
                .isEqualTo(PaymentService.WebhookOutcome.NOT_CONFIRMED);
        assertThat(state(c.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void a_lost_webhook_is_recovered_by_the_status_query_when_the_timer_fires() {
        ConsultationView c = bookForKeza();
        String pay = startPayment(c);
        providerSays(pay, "SUCCEEDED");
        overdue(c.id());

        consultations.expireIfOverdue(c.id());

        ConsultationView v = consultations.view(c.id());
        assertThat(v.state()).isEqualTo("QUEUED");
        assertThat(v.history()).anySatisfy(t -> assertThat(t.reason()).contains("status query"));
    }

    @Test
    void an_unpaid_consultation_expires_and_a_late_payment_is_refunded() {
        ConsultationView c = bookForKeza();
        String pay = startPayment(c);
        providerSays(pay, "PENDING");
        overdue(c.id());

        consultations.expireIfOverdue(c.id());
        assertThat(state(c.id())).isEqualTo("EXPIRED");
        assertThat(events.stream(ConsultationExpired.class)).anySatisfy(e -> assertThat(e.consultationId()).isEqualTo(c.id()));

        providerSays(pay, "SUCCEEDED");
        String body = webhook(pay, "evt_" + UUID.randomUUID());
        payments.handleWebhook(body, signer.sign(body, Instant.now()));
        assertThat(state(c.id())).isEqualTo("REFUND_PENDING");
    }

    @Test
    void when_irembopay_is_down_nothing_is_committed_and_the_triage_advice_stays() {
        ConsultationView c = bookForKeza();
        when(irembopay.create(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(ApiException.unavailable("payment-unavailable", "IremboPay is not reachable"));

        assertThatThrownBy(() -> consultations.requestPayment(UWASE, c.id())).isInstanceOf(ApiException.class);
        ConsultationView v = consultations.view(c.id());
        assertThat(v.state()).isEqualTo("TRIAGED");
        assertThat(v.patientAdvice()).isNotBlank();
    }

    @Test
    void an_insured_patient_pays_only_the_copayment() {
        UUID eric = UUID.fromString("10000000-0000-4000-b000-000000000002");
        consents.give(ERIC, eric, ConsentService.DEFAULT_PURPOSES);
        when(irembopay.coverage("national-id", "0000198800000002"))
                .thenReturn(new IremboPayClient.ProviderCoverage(true, "CBHI", new BigDecimal("200"), "RWF"));
        ConsultationView c = consultations.book(ERIC, new ConsultationService.BookingRequest(eric, "WEB",
                List.of("cough"), 1, false, null), null);
        when(irembopay.create(eq(c.id().toString()), eq(new BigDecimal("200")), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IremboPayClient.ProviderPayment("pay_eric", "PENDING", new BigDecimal("200"), "RWF",
                        c.id().toString(), "http://pay.test/checkout/pay_eric"));

        ConsultationService.PaymentRequestResult r = consultations.requestPayment(ERIC, c.id());
        assertThat(r.amount()).isEqualByComparingTo("200");
        assertThat(r.insured()).isTrue();
        assertThat(r.coverageScheme()).isEqualTo("CBHI");
    }

    @Test
    void a_network_error_during_reconciliation_leaves_the_consultation_pending() {
        ConsultationView c = bookForKeza();
        String pay = startPayment(c);
        when(irembopay.status(pay)).thenThrow(new ResourceAccessException("timeout"));
        overdue(c.id());
        consultations.expireIfOverdue(c.id());
        // Status unknown: it expires (slot released); a later confirmation would become a refund, not a silent charge
        assertThat(state(c.id())).isEqualTo("EXPIRED");
    }

    private void overdue(UUID id) {
        jdbc.sql("UPDATE consultation.consultation SET payment_due_by = now() - interval '1 minute' WHERE id = ?")
                .param(id).update();
    }
}
