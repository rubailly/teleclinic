package rw.teleclinic.core.triage;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import rw.teleclinic.core.shared.ApiException;

/**
 * Deterministic red-flag rules, evaluated in-process inside the user session (ADR-014). No network hop and no
 * model: this is the safety floor the asynchronous model can only raise.
 */
@Service
public class RulesEngine {

    static final int MAX_ADVICE_LENGTH = 160;

    record Condition(List<String> anySymptom, List<String> allSymptoms, List<String> ageBands,
                     Integer minDurationDays, Boolean pregnant,
                     Map<String, Double> vitalBelow, Map<String, Double> vitalAtLeast) {
    }

    record Rule(String id, Condition when, Urgency urgency, String redFlag, String advice) {
    }

    record Fallback(Urgency urgency, String advice) {
    }

    record RuleSet(String version, List<String> symptoms, @JsonProperty("default") Fallback fallback, List<Rule> rules) {
    }

    // Target: p95 under 100 ms (design document, Section 1). Registered globally; Spring Boot adds its Prometheus registry to it.
    private static final Timer TIMER = Timer.builder("teleclinic.triage.rules")
            .description("Time to evaluate the triage rules for one intake").publishPercentileHistogram()
            .register(Metrics.globalRegistry);

    private final RuleSet ruleSet;
    private final Set<String> knownSymptoms;

    RulesEngine(@Value("${teleclinic.triage.rules:classpath:rules/triage-rules-2026.1.yaml}") Resource rules) {
        this.ruleSet = load(rules);
        this.knownSymptoms = Set.copyOf(ruleSet.symptoms());
    }

    public String version() {
        return ruleSet.version();
    }

    public RuleResult evaluate(TriageIntake intake) {
        return TIMER.record(() -> evaluateNow(intake));
    }

    private RuleResult evaluateNow(TriageIntake intake) {
        for (String symptom : intake.symptoms()) {
            if (!knownSymptoms.contains(symptom)) {
                throw ApiException.unprocessable("invalid", "Unknown symptom code: " + symptom);
            }
        }
        Urgency urgency = ruleSet.fallback().urgency();
        Rule adviceFrom = null;
        List<String> redFlags = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (Rule rule : ruleSet.rules()) {
            if (!matches(rule.when(), intake)) {
                continue;
            }
            matched.add(rule.id());
            if (rule.redFlag() != null) {
                redFlags.add(rule.redFlag());
            }
            if (adviceFrom == null || rule.urgency().compareTo(adviceFrom.urgency()) > 0) {
                adviceFrom = rule;
            }
            urgency = Urgency.max(urgency, rule.urgency());
        }
        String advice = adviceFrom != null ? adviceFrom.advice() : ruleSet.fallback().advice();
        // Every case is seen by a clinician; the rules only decide how fast and what to tell the patient now
        return new RuleResult(urgency, List.copyOf(redFlags), advice, ruleSet.version(), true, List.copyOf(matched));
    }

    private static boolean matches(Condition c, TriageIntake in) {
        if (c.anySymptom() != null && c.anySymptom().stream().noneMatch(in.symptoms()::contains)) return false;
        if (c.allSymptoms() != null && !in.symptoms().containsAll(c.allSymptoms())) return false;
        if (c.ageBands() != null && !c.ageBands().contains(in.ageBand())) return false;
        if (c.minDurationDays() != null && (in.durationDays() == null || in.durationDays() < c.minDurationDays())) return false;
        if (c.pregnant() != null && c.pregnant() != in.isPregnant()) return false;
        if (c.vitalBelow() != null) {
            for (var e : c.vitalBelow().entrySet()) {
                Double v = vital(in, e.getKey());
                if (v == null || v >= e.getValue()) return false;
            }
        }
        if (c.vitalAtLeast() != null) {
            for (var e : c.vitalAtLeast().entrySet()) {
                Double v = vital(in, e.getKey());
                if (v == null || v < e.getValue()) return false;
            }
        }
        return true;
    }

    private static Double vital(TriageIntake in, String name) {
        TriageIntake.Vitals v = in.vitals();
        if (v == null) return null;
        return switch (name) {
            case "temperatureC" -> v.temperatureC();
            case "heartRate" -> v.heartRate() == null ? null : v.heartRate().doubleValue();
            case "respiratoryRate" -> v.respiratoryRate() == null ? null : v.respiratoryRate().doubleValue();
            case "spo2" -> v.spo2() == null ? null : v.spo2().doubleValue();
            default -> throw new IllegalStateException("Unknown vital in rule file: " + name);
        };
    }

    private static RuleSet load(Resource resource) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (InputStream in = resource.getInputStream()) {
            RuleSet set = yaml.readValue(in, RuleSet.class);
            // Fail at startup, not in a patient's session, if the rule file is wrong
            checkAdvice(set.fallback().advice(), "default");
            for (Rule r : set.rules()) {
                if (r.id() == null || r.when() == null || r.urgency() == null) {
                    throw new IllegalStateException("Rule is missing id, when or urgency: " + r);
                }
                checkAdvice(r.advice(), r.id());
            }
            return set;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read triage rules " + resource, e);
        }
    }

    private static void checkAdvice(String advice, String rule) {
        if (advice == null || advice.length() > MAX_ADVICE_LENGTH) {
            throw new IllegalStateException("Advice of rule " + rule + " is missing or longer than "
                    + MAX_ADVICE_LENGTH + " characters (one USSD screen)");
        }
    }
}
