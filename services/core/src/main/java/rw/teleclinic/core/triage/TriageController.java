package rw.teleclinic.core.triage;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Triage API, synchronous part: POST /triage/v1/rules/evaluate (api/teleclinic-triage-api.yaml). */
@RestController
class TriageController {

    private final RulesEngine rules;

    TriageController(RulesEngine rules) {
        this.rules = rules;
    }

    @PostMapping("/triage/v1/rules/evaluate")
    RuleResult evaluate(@Valid @RequestBody TriageIntake intake) {
        return rules.evaluate(intake);
    }
}
