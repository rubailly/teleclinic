package rw.teleclinic.core.triage;

import java.util.List;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * Asks the model for a suggestion, after the rules have answered. Carries minimised features only: no name,
 * no identifiers, no date of birth, no free text. The anonymization gateway (step 4) checks it again.
 */
@Externalized("triage.requested::#{#this.caseId()}")
public record TriageRequested(
        UUID caseId,
        String channel,
        String ageBand,
        String sex,
        boolean pregnant,
        List<String> symptoms,
        Integer durationDays,
        TriageIntake.Vitals vitals,
        Urgency rulesUrgency,
        String rulesetVersion) {
}
