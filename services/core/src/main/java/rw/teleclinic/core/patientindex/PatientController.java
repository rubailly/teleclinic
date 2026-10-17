package rw.teleclinic.core.patientindex;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PatientController {

    private final PatientDirectory directory;

    PatientController(PatientDirectory directory) {
        this.directory = directory;
    }

    /** What a clinician needs to recognise the patient in the queue (clinician role, see SecurityConfig). */
    @GetMapping("/api/clinician/patients/{id}")
    Map<String, Object> forClinician(@PathVariable UUID id) {
        Patient p = directory.require(id);
        return Map.of("patientId", p.id(), "name", p.displayName(), "ageBand", p.ageBand(LocalDate.now()), "sex", p.sex());
    }

    /** The people the signed-in user may book for: themselves and verified dependents. */
    @GetMapping("/api/me/patients")
    List<Map<String, Object>> actable(@AuthenticationPrincipal OidcUser user) {
        LocalDate today = LocalDate.now();
        return directory.actableBy(user.getSubject()).stream()
                .map(a -> Map.<String, Object>of(
                        "patientId", a.patient().id(),
                        "name", a.patient().displayName(),
                        "relation", a.relation(),
                        "relationship", a.relationship(),
                        "ageBand", a.patient().ageBand(today)))
                .toList();
    }
}
