package rw.teleclinic.core.audit;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import rw.teleclinic.core.consent.ConsentService;
import rw.teleclinic.core.patientindex.PatientDirectory;
import rw.teleclinic.core.patientindex.Relation;
import rw.teleclinic.core.shared.ApiException;

/**
 * The policy in front of every clinical read (design 4.6.4 and Appendix B): a declared purpose of use, a role
 * allowed to read for that purpose, and a basis (consent, the data subject, or break-glass with a reason).
 * Refusals are audited too, then answered with 403 and an OperationOutcome.
 */
@Service
public class ClinicalAccess {

    /** X-Purpose-Of-Use values (api/teleclinic-interop-api.yaml). */
    public enum Purpose { TREAT, PAYMENT, OPERATIONS, PATIENT_ACCESS, BREAK_GLASS }

    private static final Map<String, Set<Purpose>> ALLOWED = Map.of(
            "clinician", Set.of(Purpose.TREAT, Purpose.OPERATIONS, Purpose.BREAK_GLASS),
            "clerk", Set.of(Purpose.PAYMENT, Purpose.OPERATIONS),
            "admin", Set.of(Purpose.OPERATIONS),
            "patient", Set.of(Purpose.PATIENT_ACCESS));

    private final AuditTrail audit;
    private final ConsentService consents;
    private final PatientDirectory patients;

    ClinicalAccess(AuditTrail audit, ConsentService consents, PatientDirectory patients) {
        this.audit = audit;
        this.consents = consents;
        this.patients = patients;
    }

    /** Checks the policy, loads the data, records the read. The loader runs only if access is granted. */
    public <T> T read(Authentication who, UUID patientId, String resourceType, String resourceId, String purposeHeader,
                      String breakGlassReason, Supplier<T> loader) {
        Actor actor = Actor.of(who);
        Purpose purpose = parse(purposeHeader);
        String header = purposeHeader == null || purposeHeader.isBlank() ? null : purposeHeader.trim();
        if (purpose == null) {
            throw deny(actor, resourceType, resourceId, patientId, header, "purpose-of-use-required",
                    "X-Purpose-Of-Use is required on clinical reads: TREAT, PAYMENT, OPERATIONS, PATIENT_ACCESS or BREAK_GLASS");
        }
        boolean roleAllows = ALLOWED.entrySet().stream()
                .anyMatch(r -> actor.has(who, r.getKey()) && r.getValue().contains(purpose));
        if (!roleAllows) {
            throw deny(actor, resourceType, resourceId, patientId, purpose.name(), "purpose-not-allowed",
                    "Role " + actor.role() + " may not read clinical data for " + purpose);
        }
        String basis;
        String reason = null;
        switch (purpose) {
            case PATIENT_ACCESS -> {
                if (patients.relation(actor.subject(), patientId) == Relation.NONE) {
                    throw deny(actor, resourceType, resourceId, patientId, purpose.name(), "not-your-record",
                            "This record is not yours or a dependant's");
                }
                basis = "data-subject";
            }
            case TREAT, PAYMENT -> basis = consents.activeCovering(patientId, purpose.name())
                    .map(c -> "consent:" + c.id())
                    .orElseThrow(() -> deny(actor, resourceType, resourceId, patientId, purpose.name(), "consent-required",
                            "No active consent covering " + purpose + " for this patient"));
            case BREAK_GLASS -> {
                if (breakGlassReason == null || breakGlassReason.isBlank()) {
                    throw deny(actor, resourceType, resourceId, patientId, purpose.name(), "break-glass-reason-required",
                            "BREAK_GLASS needs X-Break-Glass-Reason");
                }
                basis = "break-glass";
                reason = breakGlassReason.strip();
            }
            default -> basis = "policy:operations";
        }
        T result = loader.get();
        audit.record(actor, "READ", resourceType, resourceId, patientId, purpose.name(), basis, AuditEntry.SUCCESS,
                reason, Map.of());
        return result;
    }

    private ApiException deny(Actor actor, String resourceType, String resourceId, UUID patientId, String purpose,
                              String code, String message) {
        audit.record(actor, "READ", resourceType, resourceId, patientId, purpose, null, AuditEntry.DENIED, code, Map.of());
        return ApiException.forbidden(code, message);
    }

    private static Purpose parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Purpose.valueOf(header.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
