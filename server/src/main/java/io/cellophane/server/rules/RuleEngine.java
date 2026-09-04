package io.cellophane.server.rules;

import java.util.concurrent.atomic.AtomicReference;

/** The live rule set. Swappable at runtime; readers always see a complete, validated set. */
public final class RuleEngine {

    private record Loaded(RuleSet rules, String yaml) {
    }

    private final AtomicReference<Loaded> current;

    public RuleEngine(RuleSet rules) {
        this.current = new AtomicReference<>(new Loaded(rules, RulesYaml.format(rules)));
    }

    public static RuleEngine fromYaml(String yaml) {
        RuleEngine engine = new RuleEngine(RuleSet.DEFAULT);
        engine.load(yaml);
        return engine;
    }

    /**
     * Replaces the rules with the given document.
     *
     * @throws RulesException if the document is invalid; the previous rules stay in force
     */
    public RuleSet load(String yaml) {
        RuleSet parsed = RulesYaml.parse(yaml);
        current.set(new Loaded(parsed, yaml));
        return parsed;
    }

    public RuleSet current() {
        return current.get().rules();
    }

    /** The document the current rules were loaded from, or a generated one for programmatic sets. */
    public String yaml() {
        return current.get().yaml();
    }

    public Decision decide(RuleContext ctx) {
        return current().decide(ctx);
    }
}
