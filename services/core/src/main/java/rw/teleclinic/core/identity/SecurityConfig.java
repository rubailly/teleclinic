package rw.teleclinic.core.identity;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Backend-for-frontend login against IremboAccount (Section 4.3, Phase 1).
 * Authorization code with PKCE; the core is a confidential client, so access and refresh tokens never
 * reach the browser. The browser holds only the HttpOnly session cookie.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    static final String REGISTRATION_ID = "iremboaccount";

    @Bean
    ClientRegistrationRepository clientRegistrations(IdentityProperties p) {
        // Endpoints are set explicitly instead of discovered: the browser-facing issuer and the
        // private back-channel address differ, and the id token "iss" is still checked against the issuer.
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("IremboAccount (stand-in)")
                .clientId(p.clientId())
                .clientSecret(p.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .issuerUri(p.issuer())
                .authorizationUri(p.issuer() + "/protocol/openid-connect/auth")
                .tokenUri(p.internalBaseUrl() + "/protocol/openid-connect/token")
                .jwkSetUri(p.internalBaseUrl() + "/protocol/openid-connect/certs")
                .userNameAttributeName("preferred_username")
                .providerConfigurationMetadata(
                        Map.of("end_session_endpoint", p.issuer() + "/protocol/openid-connect/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository registrations)
            throws Exception {
        var authorizationRequests = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        // PKCE for the confidential client too (Spring adds it by default only for public clients)
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        var logoutSuccess = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logoutSuccess.setPostLogoutRedirectUri("{baseUrl}/");

        http
            .authorizeHttpRequests(auth -> auth
                // Webhooks authenticate by signature, not by session (payments module)
                .requestMatchers("/api/public/**", "/actuator/health/**", "/login", "/error", "/webhooks/**").permitAll()
                // Static pages and assets; every API they call is still protected
                .requestMatchers("/", "/app/**", "/ui/**", "/favicon.ico").permitAll()
                // Signed callbacks from the AI trust zone; blocked at the public edge
                .requestMatchers("/internal/**").permitAll()
                // Counts and timings only, scraped by Prometheus on the internal network; blocked at the public edge
                .requestMatchers("/actuator/prometheus").permitAll()
                .requestMatchers("/api/clinician/**", "/triage/v1/cases/*/review", "/triage/v1/cases/*/suggestion").hasRole("clinician")
                // The audit trail and its explorer are for auditors (the admin role in the demo realm)
                .requestMatchers("/api/audit/**", "/fhir/AuditEvent", "/audit/**").hasRole("admin")
                // Identity review (clerks); the sync ledger is for administrators
                .requestMatchers("/identity/**", "/clerk/**").hasAnyRole("clerk", "admin")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/sync/ledger/**").hasRole("admin")
                // The sync gateway authenticates with its key (stand-in for client credentials), not a session
                .requestMatchers("/sync/inbound").permitAll()
                .requestMatchers("/triage/v1/models/kill-switch", "/triage/v1/models/activate").hasRole("admin")
                .anyRequest().authenticated())
            .requestCache(cache -> cache.requestCache(pageRequestsOnly()))
            .oauth2Login(login -> login
                .loginPage("/login")
                .successHandler(backToTheApp())
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests))
                .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(rolesFromIdToken())))
            .logout(logout -> logout.logoutSuccessHandler(logoutSuccess))
            // Browser apps read XSRF-TOKEN and send it back as X-XSRF-TOKEN; webhooks are signed instead
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(eagerCsrfToken())
                .ignoringRequestMatchers("/webhooks/**", "/internal/**", "/sync/inbound"))
            // APIs answer 401 instead of redirecting, so the apps can start the login themselves
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**")));
        return http.build();
    }

    /**
     * Only a page the user opened is worth returning to after sign-in. Without this, an app's background call
     * (for example GET /api/me answering 401) was remembered and the browser landed on raw JSON after login.
     */
    private static HttpSessionRequestCache pageRequestsOnly() {
        RequestMatcher page = request -> "GET".equals(request.getMethod())
                && SessionController.isAppPage(request.getRequestURI())
                && String.valueOf(request.getHeader("Accept")).contains("text/html");
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(page);
        // Return to the page as it was opened, without the "?continue" marker Spring adds by default
        cache.setMatchingRequestParameterName(null);
        return cache;
    }

    /** After sign-in: the app the user started from (GET /login?next=...), else the page they opened, else home. */
    private static AuthenticationSuccessHandler backToTheApp() {
        SavedRequestAwareAuthenticationSuccessHandler saved = new SavedRequestAwareAuthenticationSuccessHandler();
        saved.setDefaultTargetUrl("/");
        return (request, response, authentication) -> {
            HttpSession session = request.getSession(false);
            Object next = session == null ? null : session.getAttribute(SessionController.NEXT);
            if (next instanceof String target) {
                session.removeAttribute(SessionController.NEXT);
                response.sendRedirect(target);
                return;
            }
            saved.onAuthenticationSuccess(request, response, authentication);
        };
    }

    /** Loads the token on every request so the XSRF-TOKEN cookie is always present for the apps. */
    private static CsrfTokenRequestAttributeHandler eagerCsrfToken() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /** Maps the IremboAccount "roles" claim (patient, clinician, clerk, admin) to ROLE_* authorities. */
    private static GrantedAuthoritiesMapper rolesFromIdToken() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>(authorities);
            for (GrantedAuthority authority : authorities) {
                if (authority instanceof OidcUserAuthority oidc) {
                    Object roles = oidc.getIdToken().getClaims().get("roles");
                    if (roles instanceof Collection<?> list) {
                        list.forEach(role -> mapped.add(new SimpleGrantedAuthority("ROLE_" + role)));
                    }
                }
            }
            return List.copyOf(mapped);
        };
    }
}
