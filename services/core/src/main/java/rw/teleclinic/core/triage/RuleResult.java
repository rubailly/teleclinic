package rw.teleclinic.core.triage;

import java.util.List;

/** Triage API RuleResult. {@code patientAdvice} always fits one USSD screen. */
public record RuleResult(
        Urgency urgency,
        List<String> redFlags,
        String patientAdvice,
        String rulesetVersion,
        boolean requiresClinicianReview,
        List<String> matchedRules) {
}
