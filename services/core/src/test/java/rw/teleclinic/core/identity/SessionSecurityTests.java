package rw.teleclinic.core.identity;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import rw.teleclinic.core.IntegrationTest;

class SessionSecurityTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiWithoutSessionAnswers401NotARedirect() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void browserPageWithoutSessionGoesToLogin() throws Exception {
        mvc.perform(get("/clinician/index.html").accept("text/html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void anApiCallIsNeverTheLandingPageAfterSignIn() throws Exception {
        // A 401 on /api/me must not be remembered, or the browser lands on raw JSON after login
        mvc.perform(get("/api/me").accept("*/*"))
                .andExpect(status().isUnauthorized())
                .andExpect(request().sessionAttributeDoesNotExist("SPRING_SECURITY_SAVED_REQUEST"));
    }

    @Test
    void signInRemembersOnlyOurOwnAppsAsTheWayBack() throws Exception {
        mvc.perform(get("/login").param("next", "/clinician/"))
                .andExpect(header().string("Location", "/oauth2/authorization/iremboaccount"))
                .andExpect(request().sessionAttribute(SessionController.NEXT, "/clinician/"));
        mvc.perform(get("/login").param("next", "https://evil.example/"))
                .andExpect(request().sessionAttributeDoesNotExist(SessionController.NEXT));
        mvc.perform(get("/login").param("next", "//evil.example/app/"))
                .andExpect(request().sessionAttributeDoesNotExist(SessionController.NEXT));
    }

    @Test
    void aSignedInUserWithTheWrongRoleIsToldWhyNotShownA403() throws Exception {
        mvc.perform(get("/clerk/index.html").accept("text/html").with(oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_patient"))))
                .andExpect(header().string("Location", "/ui/denied.html?page=%2Fclerk%2Findex.html"));
        mvc.perform(get("/clinician/").accept("text/html").with(oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_patient"))))
                .andExpect(header().string("Location", "/ui/denied.html?page=%2Fclinician%2F"));
        mvc.perform(get("/api/audit/events").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_patient"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void switchingUserAsksIremboAccountForAFreshLogin() throws Exception {
        var session = mvc.perform(get("/login").param("next", "/clerk/").param("switch", "true"))
                .andExpect(header().string("Location", "/oauth2/authorization/iremboaccount"))
                .andReturn().getRequest().getSession();
        mvc.perform(get("/oauth2/authorization/iremboaccount").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(header().string("Location", containsString("prompt=login")));
        mvc.perform(get("/oauth2/authorization/iremboaccount"))
                .andExpect(header().string("Location", not(containsString("prompt=login"))));
    }

    @Test
    void authorizationRequestUsesPkceAndThePublicIssuer() throws Exception {
        mvc.perform(get("/oauth2/authorization/iremboaccount"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("http://idp.test/realms/irembo-mock/protocol/openid-connect/auth")))
                .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
                .andExpect(header().string("Location", containsString("state=")))
                .andExpect(header().string("Location", containsString("nonce=")));
    }

    @Test
    void meReturnsIdentityAndRolesButNoToken() throws Exception {
        mvc.perform(get("/api/me").with(oidcLogin()
                        .idToken(t -> t.subject("sub-123").claim("preferred_username", "patient1").claim("roles", List.of("patient")))
                        .authorities(new SimpleGrantedAuthority("ROLE_patient"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("sub-123"))
                .andExpect(jsonPath("$.username").value("patient1"))
                .andExpect(jsonPath("$.roles[0]").value("patient"))
                .andExpect(content().string(not(containsString("token"))));
    }

    @Test
    void correlationIdIsKeptOrCreated() throws Exception {
        mvc.perform(get("/api/public/ping").header("X-Correlation-Id", "demo-0001"))
                .andExpect(header().string("X-Correlation-Id", "demo-0001"));
        mvc.perform(get("/api/public/ping"))
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
