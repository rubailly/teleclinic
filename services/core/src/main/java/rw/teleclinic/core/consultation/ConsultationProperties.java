package rw.teleclinic.core.consultation;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("teleclinic.consultation")
record ConsultationProperties(BigDecimal fee, String currency, Duration paymentWindow, String publicBaseUrl) {
}
