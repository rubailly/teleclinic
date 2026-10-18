package rw.teleclinic.core.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which pool (blue or green) and version this instance belongs to. Shown in the release scene.
 * {@code faultRate} stands in for a bad build in the rollback drill: that share of API calls fails. Always 0 outside it.
 */
@ConfigurationProperties("teleclinic.release")
public record ReleaseProperties(String color, String version, double faultRate) {
}
