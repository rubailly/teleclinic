package rw.teleclinic.core;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import java.util.List;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Full application against a real PostgreSQL (one container for the whole test run), with the synthetic demo
 * seed loaded. Kafka externalization is off here; the end-to-end scripts cover it against the running stack.
 */
@SpringBootTest(properties = {
        "teleclinic.identity.issuer=http://idp.test/realms/irembo-mock",
        "teleclinic.identity.internal-base-url=http://idp.internal/realms/irembo-mock",
        "teleclinic.identity.client-secret=test-secret",
        "spring.flyway.locations=classpath:db/migration,classpath:db/demo",
        "spring.modulith.events.externalization.enabled=false",
        "teleclinic.payments.webhook-secret=test-webhook-secret",
        // Plain servlet sessions in tests; the running stack keeps them in Valkey
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
        // Tests drive the payment timer themselves
        "teleclinic.consultation.timer-initial-delay=PT1H",
        "teleclinic.triage.callback-secret=test-callback-secret",
        "teleclinic.triage.vault-key=test-vault-key",
        // Nothing listens here: the model service counts as unreachable unless a test stubs it
        "teleclinic.triage.model-service-url=http://127.0.0.1:1",
        "teleclinic.triage.anonymizer-url=http://127.0.0.1:1",
        "teleclinic.channels.gateway-key=test-gateway-key",
        "teleclinic.notifications.sms-url=http://127.0.0.1:1",
        "teleclinic.notifications.sms-key=test-gateway-key",
        // Audit entries go straight to the chain (no Kafka in tests)
        "teleclinic.audit.writer=direct"
})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    /** Subjects of the seeded demo users (config/keycloak/irembo-mock-realm.json). */
    public static final String UWASE = "00000000-0000-4000-a000-000000000001";
    public static final String ERIC = "00000000-0000-4000-a000-000000000002";
    public static final String KEZA_PATIENT_ID = "10000000-0000-4000-b000-000000000003";
    public static final String UWASE_PATIENT_ID = "10000000-0000-4000-b000-000000000001";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    protected static OidcLoginRequestPostProcessor signedInAs(String subject, String username, String role) {
        return oidcLogin()
                .idToken(t -> t.subject(subject).claim("preferred_username", username).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
