package rw.teleclinic.core.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import rw.teleclinic.core.shared.ApiException;

/**
 * Payments through IremboPay (Section 4.3, Phase 3). The webhook is a hint, not proof: every webhook is
 * verified (signature, timestamp), deduplicated by event id, and then confirmed with a server-to-server status
 * query before anything becomes PAID.
 */
@Service
public class PaymentService {

    public record PaymentIntent(UUID consultationId, String providerPaymentId, BigDecimal amount, String currency,
                                String checkoutUrl, String status) {
    }

    /** What happened to one webhook delivery. */
    public enum WebhookOutcome { CONFIRMED, DUPLICATE, NOT_CONFIRMED, IGNORED, UNKNOWN_PAYMENT }

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final JdbcClient jdbc;
    private final IremboPayClient irembopay;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final WebhookSignature signature;
    private final Clock clock;

    PaymentService(JdbcClient jdbc, IremboPayClient irembopay, ApplicationEventPublisher events, ObjectMapper json,
                   PaymentsProperties props) {
        this.jdbc = jdbc;
        this.irembopay = irembopay;
        this.events = events;
        this.json = json;
        this.signature = new WebhookSignature(props.webhookSecret(), props.signatureTolerance());
        this.clock = Clock.systemUTC();
    }

    /**
     * Creates the payment at IremboPay, or returns the one already created for this consultation. The
     * consultation id is both the reference and the idempotency key, so a retry after a timeout cannot charge twice.
     */
    @Transactional
    public PaymentIntent start(UUID consultationId, BigDecimal amount, String currency, String description,
                               String returnUrl) {
        Optional<PaymentIntent> existing = findByConsultation(consultationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        IremboPayClient.ProviderPayment p = irembopay.create(consultationId.toString(), amount, currency,
                consultationId.toString(), description, returnUrl);
        jdbc.sql("""
                        INSERT INTO payments.payment (id, consultation_id, provider_payment_id, amount, currency, status, checkout_url)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (consultation_id) DO NOTHING""")
                .params(UUID.randomUUID(), consultationId, p.paymentId(), amount, currency, p.status(), p.checkoutUrl())
                .update();
        return findByConsultation(consultationId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findByConsultation(UUID consultationId) {
        return jdbc.sql("SELECT * FROM payments.payment WHERE consultation_id = ?").param(consultationId)
                .query((rs, n) -> new PaymentIntent(consultationId, rs.getString("provider_payment_id"),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("checkout_url"),
                        rs.getString("status")))
                .optional();
    }

    /** Handles one webhook delivery. Throws 401 for a bad signature; everything else is acknowledged with 200. */
    @Transactional
    public WebhookOutcome handleWebhook(String body, String signatureHeader) {
        if (!signature.isValid(signatureHeader, body, clock.instant())) {
            log.warn("Webhook rejected: invalid or expired signature");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid-signature", "Webhook signature is invalid or expired");
        }
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (Exception e) {
            throw ApiException.unprocessable("invalid", "Webhook body is not JSON");
        }
        String eventId = event.path("eventId").asText(null);
        String providerPaymentId = event.path("paymentId").asText(null);
        String type = event.path("type").asText("");
        if (eventId == null || providerPaymentId == null) {
            throw ApiException.unprocessable("invalid", "Webhook without eventId or paymentId");
        }
        // The primary key on event_id makes a second delivery of the same event a no-op
        int inserted = jdbc.sql("""
                        INSERT INTO payments.webhook_event (event_id, provider_payment_id, event_type, outcome)
                        VALUES (?, ?, ?, 'RECEIVED') ON CONFLICT (event_id) DO NOTHING""")
                .params(eventId, providerPaymentId, type).update();
        if (inserted == 0) {
            log.info("Webhook {} already processed, acknowledged and ignored", eventId);
            return WebhookOutcome.DUPLICATE;
        }
        WebhookOutcome outcome = type.equals("payment.succeeded")
                ? confirmWithProvider(providerPaymentId, "webhook " + eventId)
                : WebhookOutcome.IGNORED;
        jdbc.sql("UPDATE payments.webhook_event SET outcome = ? WHERE event_id = ?").params(outcome.name(), eventId).update();
        log.info("Webhook {} for {}: {}", eventId, providerPaymentId, outcome);
        return outcome;
    }

    /**
     * Reconciliation when no webhook arrived (lost webhook): asks IremboPay directly. Used by the payment timer
     * before a consultation is expired.
     */
    @Transactional
    public boolean reconcile(UUID consultationId) {
        return findByConsultation(consultationId)
                .map(p -> {
                    try {
                        return confirmWithProvider(p.providerPaymentId(), "status query (no webhook)") == WebhookOutcome.CONFIRMED;
                    } catch (RestClientException e) {
                        log.warn("Reconciliation of {} failed: {}", consultationId, e.getMessage());
                        return false;
                    }
                })
                .orElse(false);
    }

    private WebhookOutcome confirmWithProvider(String providerPaymentId, String source) {
        Optional<Row> row = jdbc.sql("SELECT * FROM payments.payment WHERE provider_payment_id = ? FOR UPDATE")
                .param(providerPaymentId)
                .query((rs, n) -> new Row(rs.getObject("consultation_id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("status")))
                .optional();
        if (row.isEmpty()) {
            return WebhookOutcome.UNKNOWN_PAYMENT;
        }
        if ("SUCCEEDED".equals(row.get().status())) {
            return WebhookOutcome.DUPLICATE;
        }
        // Server-to-server confirmation: a webhook alone never unlocks a paid consultation
        IremboPayClient.ProviderPayment provider = irembopay.status(providerPaymentId);
        boolean paid = "SUCCEEDED".equals(provider.status())
                && provider.amount().compareTo(row.get().amount()) == 0
                && provider.currency().equals(row.get().currency());
        if (!paid) {
            log.warn("Payment {} not confirmed by IremboPay (status {}, amount {}); claim from {} ignored",
                    providerPaymentId, provider.status(), provider.amount(), source);
            return WebhookOutcome.NOT_CONFIRMED;
        }
        jdbc.sql("UPDATE payments.payment SET status = 'SUCCEEDED', updated_at = ? WHERE provider_payment_id = ?")
                .params(java.sql.Timestamp.from(Instant.now(clock)), providerPaymentId).update();
        // Same transaction: the consultation becomes PAID and its outbox event is stored together with this update
        events.publishEvent(new PaymentConfirmed(row.get().consultationId(), providerPaymentId, row.get().amount(),
                row.get().currency(), source));
        return WebhookOutcome.CONFIRMED;
    }

    private record Row(UUID consultationId, BigDecimal amount, String currency, String status) {
    }
}
