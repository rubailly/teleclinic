package rw.teleclinic.core.matching;

import java.time.LocalDate;

/** A patient record as a facility EMR holds it, reduced to what matching needs. */
public record EmrPatient(String emrUuid, String givenName, String familyName, String sex, LocalDate birthDate,
                         String phone, String nationalId, String upi, String emrIdentifier) {

    public String displayName() {
        return (givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName);
    }
}
