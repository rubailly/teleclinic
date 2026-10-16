package rw.teleclinic.core.identity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where IremboAccount lives. {@code issuer} is the public URL (what the browser is sent to and what the
 * id token's "iss" must equal); {@code internalBaseUrl} is the same realm reached on the private network.
 */
@Validated
@ConfigurationProperties("teleclinic.identity")
record IdentityProperties(
        @NotBlank String issuer,
        @NotBlank String internalBaseUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret) {
}
