package rw.teleclinic.core.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.HtmlUtils;

/** Login entry point and the signed-in user's identity. Never returns a token. */
@RestController
class SessionController {

    @GetMapping("/login")
    RedirectView login() {
        return new RedirectView("/oauth2/authorization/" + SecurityConfig.REGISTRATION_ID);
    }

    @GetMapping("/api/me")
    Map<String, Object> me(@AuthenticationPrincipal OidcUser user, Authentication authentication) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("subject", user.getSubject());
        me.put("username", user.getPreferredUsername());
        me.put("name", user.getFullName());
        me.put("issuer", String.valueOf(user.getIssuer()));
        me.put("roles", roles(authentication));
        me.put("authenticatedAt", String.valueOf(user.getAuthenticatedAt()));
        return me;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    String home(@AuthenticationPrincipal OidcUser user, CsrfToken csrf) {
        String name = HtmlUtils.htmlEscape(String.valueOf(user.getFullName()));
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>TeleClinic core</title></head>
                <body style="font-family:sans-serif;max-width:40rem;margin:2rem auto">
                <h1>TeleClinic core</h1>
                <p>Signed in through IremboAccount (labelled stand-in: Keycloak) as <b>%s</b>.</p>
                <p><a href="/api/me">/api/me</a> shows the session identity. No token is sent to this browser.</p>
                <form method="post" action="/logout"><input type="hidden" name="%s" value="%s"><button>Sign out</button></form>
                </body></html>
                """.formatted(name, csrf.getParameterName(), csrf.getToken());
    }

    /** Roles from the authentication (mapped from the "roles" claim), not from the raw OIDC principal. */
    private static List<String> roles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .sorted()
                .toList();
    }
}
