package rw.teleclinic.core.matching;

import java.util.Map;
import java.util.UUID;

/** Link decisions, for the sync gateway: identifiers to offer to the record in that EMR, or to take back on unlink. */
public final class IdentityEvents {

    private IdentityEvents() {
    }

    public record PatientLinked(UUID linkId, UUID patientId, String system, String emrUuid, Map<String, String> identifiers) {
    }

    public record PatientUnlinked(UUID linkId, UUID patientId, String system, String emrUuid, Map<String, String> identifiers) {
    }
}
