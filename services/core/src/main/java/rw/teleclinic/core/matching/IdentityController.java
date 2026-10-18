package rw.teleclinic.core.matching;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.audit.Actor;
import rw.teleclinic.core.patientindex.Patient;
import rw.teleclinic.core.patientindex.PatientDirectory;
import rw.teleclinic.core.shared.ApiException;

/** Identity API (api/teleclinic-interop-api.yaml): the review queue and link decisions, for clerks. */
@RestController
class IdentityController {

    record LinkDecision(String id, String sourcePatient, String targetPatient, String linkType, Double score,
                        String decidedBy, String reason, Instant decidedAt) {
    }

    record Dismissal(String reason) {
    }

    private final IdentityMatcher matcher;
    private final PatientDirectory patients;

    IdentityController(IdentityMatcher matcher, PatientDirectory patients) {
        this.matcher = matcher;
        this.patients = patients;
    }

    /** Medium-confidence pairs with side-by-side evidence (left: TeleClinic, right: the EMR record). */
    @GetMapping("/identity/review-queue")
    List<Map<String, Object>> reviewQueue() {
        return matcher.queue().stream().map(this::item).toList();
    }

    @PostMapping("/identity/links")
    @ResponseStatus(HttpStatus.CREATED)
    LinkDecision link(Authentication who, @RequestBody LinkDecision decision) {
        if (decision.reason() == null || decision.reason().isBlank()) {
            throw ApiException.unprocessable("invalid", "A link decision needs a reason");
        }
        UUID patientId = UUID.fromString(ref(decision.sourcePatient()));
        IdentityMatcher.Link l = matcher.link(patientId, ref(decision.targetPatient()), Actor.of(who), decision.reason());
        return decision(l);
    }

    @DeleteMapping("/identity/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(Authentication who, @PathVariable UUID linkId,
                @RequestParam(defaultValue = "wrong link corrected by a clerk") String reason) {
        matcher.unlink(linkId, Actor.of(who), reason);
    }

    @PostMapping("/identity/review-queue/{candidateId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void dismiss(Authentication who, @PathVariable UUID candidateId, @RequestBody Dismissal body) {
        matcher.dismiss(candidateId, Actor.of(who), body == null || body.reason() == null ? "not the same person" : body.reason());
    }

    @GetMapping("/identity/links")
    List<Map<String, Object>> links() {
        return matcher.activeLinks().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("link", decision(l));
            m.put("patient", fhir(patients.require(l.patientId())));
            m.put("writtenIdentifiers", l.writtenIdentifiers());
            return m;
        }).toList();
    }

    /** Pairs the matcher will never offer: both records carry a national ID and they differ. */
    @GetMapping("/identity/blocked")
    List<Map<String, Object>> blocked() {
        return matcher.blocked().stream().map(this::item).toList();
    }

    private Map<String, Object> item(IdentityMatcher.Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("left", fhir(patients.require(c.patientId())));
        m.put("right", fhir(c.emr()));
        m.put("score", c.score());
        m.put("agreeingFields", c.agreeing());
        m.put("conflictingFields", c.conflicting());
        return m;
    }

    private static LinkDecision decision(IdentityMatcher.Link l) {
        return new LinkDecision(l.id().toString(), "Patient/" + l.patientId(), "Patient/" + l.emrUuid(), "seealso",
                l.score(), l.decidedBy(), l.reason(), l.decidedAt());
    }

    private static String ref(String reference) {
        if (reference == null || !reference.startsWith("Patient/")) {
            throw ApiException.unprocessable("invalid", "Patient references look like Patient/{id}");
        }
        return reference.substring("Patient/".length());
    }

    static Map<String, Object> fhir(Patient p) {
        return resource(p.id().toString(), "TeleClinic", p.givenName(), p.familyName(), p.sex(), p.birthDate(), p.phone(),
                identifiers(p.nationalId(), p.upi(), null));
    }

    static Map<String, Object> fhir(EmrPatient e) {
        return resource(e.emrUuid(), "OpenMRS (facility EMR)", e.givenName(), e.familyName(), e.sex(), e.birthDate(), e.phone(),
                identifiers(e.nationalId(), e.upi(), e.emrIdentifier()));
    }

    private static List<Map<String, String>> identifiers(String nationalId, String upi, String emrId) {
        List<Map<String, String>> ids = new ArrayList<>();
        if (nationalId != null) {
            ids.add(Map.of("type", "National ID (NIDA)", "value", nationalId));
        }
        if (upi != null) {
            ids.add(Map.of("type", "UPI", "value", upi));
        }
        if (emrId != null) {
            ids.add(Map.of("type", "EMR identifier", "value", emrId));
        }
        return ids;
    }

    private static Map<String, Object> resource(String id, String source, String given, String family, String sex,
                                                LocalDate birth, String phone, List<Map<String, String>> ids) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "Patient");
        r.put("id", id);
        r.put("meta", Map.of("source", source));
        r.put("name", List.of(Map.of("given", List.of(given == null ? "" : given), "family", family == null ? "" : family)));
        r.put("gender", "F".equalsIgnoreCase(sex) || "female".equalsIgnoreCase(sex) ? "female"
                : "M".equalsIgnoreCase(sex) || "male".equalsIgnoreCase(sex) ? "male" : "unknown");
        r.put("birthDate", birth == null ? null : birth.toString());
        r.put("telecom", phone == null ? List.of() : List.of(Map.of("system", "phone", "value", phone)));
        r.put("identifier", ids.stream().map(i -> Map.of("type", Map.of("text", i.get("type")), "value", i.get("value"))).toList());
        return r;
    }
}
