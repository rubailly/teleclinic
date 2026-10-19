package rw.teleclinic.core.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/** Login entry point and the signed-in user's identity. Never returns a token. */
@RestController
class SessionController {

    static final String NEXT = "tc.next";
    /** Where sign-in may send the browser back to: our own apps only, so "next" cannot be used to redirect away */
    private static final Pattern APP_PAGE = Pattern.compile("/(app|clinician|clerk|audit)(/[A-Za-z0-9._/-]*)?");

    static boolean isAppPage(String path) {
        return path != null && APP_PAGE.matcher(path).matches();
    }

    @GetMapping("/login")
    RedirectView login(@RequestParam(required = false) String next, HttpSession session) {
        if (isAppPage(next)) {
            session.setAttribute(NEXT, next);
        }
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

    /** The patient app is the front door; after sign-in or sign-out the browser lands here. */
    @GetMapping("/")
    RedirectView home() {
        return new RedirectView("/app/");
    }

    private static List<String> roles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .sorted()
                .toList();
    }
}
