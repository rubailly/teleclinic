package rw.teleclinic.core.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import rw.teleclinic.core.shared.ApiException;

class RulesEngineTests {

    private final RulesEngine engine = new RulesEngine(new ClassPathResource("rules/triage-rules-2026.1.yaml"));

    private static TriageIntake intake(String ageBand, List<String> symptoms, Integer days, Double tempC, Integer spo2) {
        return new TriageIntake("c-test", "USSD", ageBand, "F", false, symptoms, days,
                new TriageIntake.Vitals(tempC, null, null, spo2));
    }

    @Test
    void worked_example_from_the_design_document() {
        // Section 6.4: child 5-11, fever and fast breathing for 2 days, 39.4 C
        RuleResult r = engine.evaluate(intake("5-11", List.of("fever", "fast_breathing"), 2, 39.4, null));
        assertThat(r.urgency()).isEqualTo(Urgency.URGENT);
        assertThat(r.redFlags()).containsExactly("fast_breathing_child");
        assertThat(r.patientAdvice()).isEqualTo(
                "Go to the nearest health facility today. If breathing gets worse, call the emergency number.");
        assertThat(r.rulesetVersion()).isEqualTo("2026.1");
        assertThat(r.requiresClinicianReview()).isTrue();
    }

    @Test
    void the_same_symptoms_in_an_adult_are_not_a_child_red_flag() {
        RuleResult r = engine.evaluate(intake("18-39", List.of("fever", "fast_breathing"), 2, 38.0, null));
        assertThat(r.urgency()).isEqualTo(Urgency.SEMI_URGENT);
        assertThat(r.redFlags()).isEmpty();
    }

    @Test
    void danger_signs_are_emergencies_whatever_else_is_reported() {
        RuleResult r = engine.evaluate(intake("40-64", List.of("headache", "chest_pain"), 1, null, null));
        assertThat(r.urgency()).isEqualTo(Urgency.EMERGENCY);
        assertThat(r.redFlags()).contains("danger_sign");
    }

    @Test
    void low_oxygen_is_an_emergency_and_the_highest_urgency_wins() {
        RuleResult r = engine.evaluate(intake("5-11", List.of("fever", "fast_breathing"), 4, 39.8, 90));
        assertThat(r.urgency()).isEqualTo(Urgency.EMERGENCY);
        assertThat(r.redFlags()).contains("low_oxygen", "fast_breathing_child", "high_fever");
        assertThat(r.matchedRules()).contains("prolonged_fever", "fever");
    }

    @Test
    void no_symptom_rule_matches_means_routine() {
        RuleResult r = engine.evaluate(intake("18-39", List.of("rash"), 1, null, null));
        assertThat(r.urgency()).isEqualTo(Urgency.ROUTINE);
    }

    @Test
    void unknown_symptom_codes_are_refused() {
        assertThatThrownBy(() -> engine.evaluate(intake("18-39", List.of("feels_bad"), 1, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("feels_bad");
    }

    @Test
    void every_advice_fits_one_ussd_screen() {
        for (String band : List.of("0-4", "5-11", "18-39")) {
            RuleResult r = engine.evaluate(intake(band, List.of("fever", "fast_breathing", "bleeding"), 5, 40.0, 85));
            assertThat(r.patientAdvice()).hasSizeLessThanOrEqualTo(RulesEngine.MAX_ADVICE_LENGTH);
        }
    }
}
