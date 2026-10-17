package rw.teleclinic.core.patientindex;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/** A person TeleClinic holds a record for. Identifiers are sliced by system; any of them may be absent. */
public record Patient(
        UUID id,
        String iremboSubject,
        String nationalId,
        String upi,
        String givenName,
        String familyName,
        LocalDate birthDate,
        String sex,
        String phone) {

    /** Age band used for triage and the model, so the date of birth never leaves the core (data minimisation). */
    public String ageBand(LocalDate today) {
        int age = Period.between(birthDate, today).getYears();
        if (age < 5) return "0-4";
        if (age < 12) return "5-11";
        if (age < 18) return "12-17";
        if (age < 40) return "18-39";
        if (age < 65) return "40-64";
        return "65+";
    }

    public String displayName() {
        return givenName + " " + familyName;
    }
}
