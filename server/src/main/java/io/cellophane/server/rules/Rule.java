package io.cellophane.server.rules;

import java.util.Objects;

/** One entry of the rule list: a name for the timeline, what it matches and what it does. */
public record Rule(String name, Match match, RuleAction action) {

    public Rule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(action, "action");
    }
}
