package rw.teleclinic.core.payments;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Health insurance coverage (CBHI/Mutuelle, RSSB schemes), checked before asking for payment so insured
 * patients pay only their share (assumption A12). If the check is unavailable the patient pays the full fee
 * and can claim afterwards; the booking is never blocked by it.
 */
@Service
public class CoverageService {

    public record Coverage(boolean covered, String scheme, BigDecimal copayment, String note) {
        public static Coverage none(String note) {
            return new Coverage(false, null, null, note);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);
    private final IremboPayClient client;

    CoverageService(IremboPayClient client) {
        this.client = client;
    }

    public Coverage check(String nationalId, String upi) {
        try {
            IremboPayClient.ProviderCoverage c = nationalId != null
                    ? client.coverage("national-id", nationalId)
                    : upi != null ? client.coverage("upi", upi) : null;
            if (c == null || !c.covered()) {
                return Coverage.none("not covered");
            }
            return new Coverage(true, c.scheme(), c.copayment(), "covered");
        } catch (RestClientException e) {
            log.warn("Coverage check unavailable, charging the full fee: {}", e.getMessage());
            return Coverage.none("coverage check unavailable; full fee, claim afterwards");
        }
    }
}
