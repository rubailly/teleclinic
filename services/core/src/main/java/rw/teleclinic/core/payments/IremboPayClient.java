package rw.teleclinic.core.payments;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import rw.teleclinic.core.shared.ApiException;

/** The port to IremboPay (PaymentPort in Section 4.4). A different provider would be another adapter. */
@Component
class IremboPayClient {

    record ProviderPayment(String paymentId, String status, BigDecimal amount, String currency, String reference,
                           String checkoutUrl) {
    }

    record ProviderCoverage(boolean covered, String scheme, BigDecimal copayment, String currency) {
    }

    private final RestClient http;
    private final RestClient insurance;
    private final PaymentsProperties props;

    // Boot's builder carries the application's Jackson setup, which ignores fields we do not use
    IremboPayClient(PaymentsProperties props, RestClient.Builder builder, org.springframework.core.env.Environment env) {
        this.props = props;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.clone().baseUrl(props.baseUrl()).requestFactory(timeouts).build();
        this.insurance = builder.clone()
                .baseUrl(env.getProperty("teleclinic.insurance.base-url", props.baseUrl()))
                .requestFactory(timeouts).build();
    }

    ProviderPayment create(String idempotencyKey, BigDecimal amount, String currency, String reference,
                           String description, String returnUrl) {
        try {
            return http.post().uri("/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request(amount, currency, reference, description, returnUrl))
                    .retrieve().body(ProviderPayment.class);
        } catch (RestClientException e) {
            throw ApiException.unavailable("payment-unavailable",
                    "IremboPay is not reachable right now; nothing was charged. Please try again in a moment.");
        }
    }

    private Map<String, Object> request(BigDecimal amount, String currency, String reference, String description,
                                        String returnUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("reference", reference);
        body.put("description", description);
        body.put("callbackUrl", props.callbackUrl());
        body.put("channel", "WEB");
        if (returnUrl != null) {
            body.put("returnUrl", returnUrl);
        }
        return body;
    }

    ProviderPayment status(String providerPaymentId) {
        return http.get().uri("/v1/payments/{id}", providerPaymentId).retrieve().body(ProviderPayment.class);
    }

    ProviderCoverage coverage(String system, String value) {
        // Template variable, so the "|" between system and value is encoded exactly once
        return insurance.get()
                .uri(u -> u.path("/insurance/v1/coverage").queryParam("identifier", "{id}").build(system + "|" + value))
                .retrieve().body(ProviderCoverage.class);
    }
}
