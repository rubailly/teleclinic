package rw.teleclinic.core.fhir;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import rw.teleclinic.core.audit.ClinicalAccess;
import rw.teleclinic.core.patientindex.Patient;
import rw.teleclinic.core.patientindex.PatientDirectory;
import rw.teleclinic.core.shared.ApiException;

/** GET /fhir/Patient/{id} (api/teleclinic-interop-api.yaml). X-Purpose-Of-Use is required. */
@RestController
class PatientResource {

    // Identifier systems are placeholders until agreed with the registry owners (design assumption A6)
    static final String NATIONAL_ID = "https://fhir.teleclinic.example/sid/rw-national-id";
    static final String UPI = "https://fhir.teleclinic.example/sid/rw-upi";
    static final String IREMBO = "https://account.irembo.example/sub";

    private final PatientDirectory patients;
    private final ClinicalAccess access;

    PatientResource(PatientDirectory patients, ClinicalAccess access) {
        this.patients = patients;
        this.access = access;
    }

    @GetMapping(value = "/fhir/Patient/{id}", produces = "application/fhir+json")
    ResponseEntity<Map<String, Object>> read(Authentication who, @PathVariable String id,
                                             @RequestHeader(value = "X-Purpose-Of-Use", required = false) String purpose,
                                             @RequestHeader(value = "X-Break-Glass-Reason", required = false) String reason) {
        UUID patientId;
        try {
            patientId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Patient/" + id + " not found");
        }
        Map<String, Object> body = access.read(who, patientId, "Patient", id, purpose, reason,
                () -> fhir(patients.findById(patientId).orElseThrow(() -> ApiException.notFound("Patient/" + id + " not found"))));
        return ResponseEntity.ok().header("Content-Type", "application/fhir+json").eTag("W/\"1\"").body(body);
    }

    /** What a clinician needs to recognise the patient in the queue; a clinical read like any other. */
    @GetMapping("/api/clinician/patients/{id}")
    Map<String, Object> forClinician(Authentication who, @PathVariable UUID id,
                                     @RequestHeader(value = "X-Purpose-Of-Use", required = false) String purpose,
                                     @RequestHeader(value = "X-Break-Glass-Reason", required = false) String reason) {
        return access.read(who, id, "PatientSummary", id.toString(), purpose, reason, () -> {
            Patient p = patients.require(id);
            return Map.of("patientId", p.id(), "name", p.displayName(), "ageBand", p.ageBand(LocalDate.now()), "sex", p.sex());
        });
    }

    static Map<String, Object> fhir(Patient p) {
        List<Map<String, Object>> identifiers = new ArrayList<>();
        if (p.nationalId() != null) {
            identifiers.add(Map.of("system", NATIONAL_ID, "value", p.nationalId()));
        }
        if (p.upi() != null) {
            identifiers.add(Map.of("system", UPI, "value", p.upi()));
        }
        if (p.iremboSubject() != null) {
            identifiers.add(Map.of("system", IREMBO, "value", p.iremboSubject()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "Patient");
        r.put("id", p.id().toString());
        r.put("meta", Map.of("versionId", "1", "tag", List.of(Map.of("system", "https://fhir.teleclinic.example/origin", "code", "teleclinic"))));
        r.put("identifier", identifiers);
        r.put("name", List.of(Map.of("use", "official", "family", p.familyName(), "given", List.of(p.givenName()))));
        r.put("gender", switch (p.sex() == null ? "" : p.sex()) {
            case "F" -> "female";
            case "M" -> "male";
            default -> "unknown";
        });
        r.put("birthDate", p.birthDate().toString());
        if (p.phone() != null) {
            r.put("telecom", List.of(Map.of("system", "phone", "value", p.phone(), "use", "mobile")));
        }
        return r;
    }
}
