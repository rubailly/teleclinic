package rw.teleclinic.core.triage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param modelServiceUrl  the model service in the AI trust zone (internal network only)
 * @param anonymizerUrl    the anonymization gateway; the core alone may resolve its pseudonym tokens
 * @param vaultKey         credential that lets the core (and nothing else) read the pseudonym vault
 * @param callbackSecret   shared secret for signed suggestion callbacks from the AI trust zone
 * @param slaScale         multiplies the review deadlines; the demo shortens them to show the timeout
 */
@ConfigurationProperties("teleclinic.triage")
record TriageProperties(String modelServiceUrl, String anonymizerUrl, String vaultKey, String callbackSecret,
                        Duration callbackTolerance, double slaScale) {
}
