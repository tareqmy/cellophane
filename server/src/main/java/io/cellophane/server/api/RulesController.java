package io.cellophane.server.api;

import io.cellophane.server.rules.RuleEngine;
import io.cellophane.server.rules.RuleSet;
import io.cellophane.server.rules.RulesException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read and replace the operator's behaviour rules at runtime. Both directions speak the YAML document. */
@RestController
@RequestMapping("/api/v1/rules")
class RulesController {

    static final String YAML = "application/yaml";

    private final RuleEngine rules;

    RulesController(RuleEngine rules) {
        this.rules = rules;
    }

    @GetMapping(produces = YAML)
    String get() {
        return rules.yaml();
    }

    /** Replaces the rules. An invalid document is refused with a 400 and the explanation; the old rules stay. */
    @PutMapping(consumes = {YAML, "application/x-yaml", "text/yaml", "text/plain", "application/octet-stream"})
    RulesApplied put(@RequestBody String yaml) {
        try {
            RuleSet set = rules.load(yaml);
            return new RulesApplied(set.rules().size());
        } catch (RulesException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    record RulesApplied(int rules) {
    }
}
