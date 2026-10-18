package rw.teleclinic.core.audit;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Who is acting: subject, display name and the role that matters most for the audit (clinician over patient). */
public record Actor(String subject, String role, String name) {

    private static final List<String> ROLES = List.of("admin", "clinician", "clerk", "patient");

    public static Actor of(Authentication auth) {
        String role = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).filter(ROLES::contains)
                .min((a, b) -> ROLES.indexOf(a) - ROLES.indexOf(b)).orElse("user");
        if (auth.getPrincipal() instanceof OidcUser user) {
            String name = user.getFullName() != null ? user.getFullName() : user.getPreferredUsername();
            return new Actor(user.getSubject(), role, name);
        }
        return new Actor(auth.getName(), role, auth.getName());
    }

    boolean has(Authentication auth, String r) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + r));
    }
}
