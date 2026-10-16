package rw.teleclinic.core.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Which pool (blue or green) and version this instance belongs to. Shown in the release scene. */
@ConfigurationProperties("teleclinic.release")
public record ReleaseProperties(String color, String version) {
}
