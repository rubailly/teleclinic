package rw.teleclinic.core.payments;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("teleclinic.payments")
record PaymentsProperties(String baseUrl, String webhookSecret, String callbackUrl, Duration signatureTolerance) {
}
