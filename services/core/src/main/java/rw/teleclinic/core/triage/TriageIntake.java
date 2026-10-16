package rw.teleclinic.core.triage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Minimised triage input (Triage API, TriageIntake): age band and sex instead of date of birth and name,
 * coded symptoms instead of free text.
 */
public record TriageIntake(
        String caseId,
        @NotBlank String channel,
        @NotBlank String ageBand,
        @NotBlank String sex,
        Boolean pregnant,
        @NotEmpty List<String> symptoms,
        Integer durationDays,
        Vitals vitals) {

    public record Vitals(Double temperatureC, Integer heartRate, Integer respiratoryRate, Integer spo2) {
    }

    boolean isPregnant() {
        return Boolean.TRUE.equals(pregnant);
    }
}
