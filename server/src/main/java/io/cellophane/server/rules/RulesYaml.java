package io.cellophane.server.rules;

import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.receipt.DeliveryReceipt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads and writes the rules document:
 * <pre>
 * rules:
 *   - match: { to: "^88017" }
 *     accept: { dlr: DELIVRD, after: 2s }
 *   - match: { to: "^88019" }
 *     accept:
 *       dlr: [ { DELIVRD: 80%, after: 3s }, { UNDELIV: 20%, after: 30s } ]
 *   - match: { text: "(?i)spam" }
 *     reject: ESME_RINVDSTADR
 *   - default:
 *       accept: { dlr: DELIVRD, after: 500ms }
 * </pre>
 */
public final class RulesYaml {

    private static final Set<String> ACTIONS = Set.of("accept", "reject");
    private static final Set<String> PLANNED = Set.of("throttle", "latency", "disconnect");
    private static final Set<String> MATCH_KEYS = Set.of("to", "from", "account", "text");

    private RulesYaml() {
    }

    public static RuleSet parse(String yaml) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new RulesException("not valid YAML: " + e.getMessage(), e);
        }
        if (root == null) {
            return RuleSet.DEFAULT;
        }
        List<?> items;
        if (root instanceof List<?> list) {
            items = list;
        } else if (root instanceof Map<?, ?> map && map.get("rules") instanceof List<?> list) {
            items = list;
        } else if (root instanceof Map<?, ?> map && map.containsKey("rules") && map.get("rules") == null) {
            items = List.of();
        } else {
            throw new RulesException("expected a top-level 'rules:' list");
        }

        List<Rule> rules = new ArrayList<>();
        RuleAction defaultAction = null;
        for (int i = 0; i < items.size(); i++) {
            String where = "rule " + (i + 1);
            if (!(items.get(i) instanceof Map<?, ?> item)) {
                throw new RulesException(where + ": each rule must be a mapping");
            }
            if (item.containsKey("default")) {
                if (defaultAction != null) {
                    throw new RulesException(where + ": only one default is allowed");
                }
                if (item.size() != 1) {
                    throw new RulesException(where + ": 'default' cannot be combined with other keys");
                }
                defaultAction = action(item.get("default"), where + " (default)");
                continue;
            }
            Match match = item.containsKey("match") ? match(item.get("match"), where) : Match.ANY;
            String name = item.get("name") == null ? where : String.valueOf(item.get("name"));
            Map<?, ?> actionKeys = new java.util.LinkedHashMap<>(item);
            actionKeys.remove("match");
            actionKeys.remove("name");
            rules.add(new Rule(name, match, action(actionKeys, where)));
        }
        return new RuleSet(rules, defaultAction == null ? RuleSet.BUILT_IN_DEFAULT : defaultAction);
    }

    private static Match match(Object node, String where) {
        if (node == null) {
            return Match.ANY;
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new RulesException(where + ": 'match' must be a mapping of to/from/account/text");
        }
        for (Object key : map.keySet()) {
            if (!MATCH_KEYS.contains(String.valueOf(key))) {
                throw new RulesException(where + ": unknown match field '" + key + "' (use to, from, account, text)");
            }
        }
        return new Match(regex(map.get("to"), where, "to"), regex(map.get("from"), where, "from"),
                regex(map.get("account"), where, "account"), regex(map.get("text"), where, "text"));
    }

    private static Pattern regex(Object value, String where, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Pattern.compile(String.valueOf(value));
        } catch (PatternSyntaxException e) {
            throw new RulesException(where + ": match." + field + " is not a valid regex: " + e.getDescription());
        }
    }

    private static RuleAction action(Object node, String where) {
        if (!(node instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new RulesException(where + ": needs exactly one action (accept or reject)");
        }
        if (map.size() != 1) {
            throw new RulesException(where + ": has several actions " + map.keySet() + "; use one");
        }
        String key = String.valueOf(map.keySet().iterator().next());
        Object value = map.get(key);
        if (PLANNED.contains(key)) {
            throw new RulesException(where + ": action '" + key + "' is not supported yet");
        }
        if (!ACTIONS.contains(key)) {
            throw new RulesException(where + ": unknown action '" + key + "' (use accept or reject)");
        }
        return key.equals("accept") ? accept(value, where) : reject(value, where);
    }

    private static Accept accept(Object value, String where) {
        if (value == null) {
            return Accept.silent();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new RulesException(where + ": 'accept' must be a mapping like { dlr: DELIVRD, after: 2s }");
        }
        for (Object key : map.keySet()) {
            if (!Set.of("dlr", "after").contains(String.valueOf(key))) {
                throw new RulesException(where + ": unknown accept option '" + key + "' (use dlr, after)");
            }
        }
        Delay after = map.containsKey("after") ? delay(map.get("after"), where) : Delay.NONE;
        Object dlr = map.get("dlr");
        if (dlr == null || dlr instanceof String) {
            DeliveryReceipt.State state = dlr == null ? null : state(dlr, where);
            return new Accept(List.of(new Accept.Outcome(state, after, 1)));
        }
        if (dlr instanceof List<?> list && !list.isEmpty()) {
            List<Accept.Outcome> outcomes = new ArrayList<>();
            for (Object entry : list) {
                outcomes.add(outcome(entry, after, where));
            }
            return new Accept(outcomes);
        }
        throw new RulesException(where + ": 'dlr' must be a state like DELIVRD, none, or a list of weighted states");
    }

    private static Accept.Outcome outcome(Object entry, Delay defaultDelay, String where) {
        if (entry instanceof String s) {
            return new Accept.Outcome(state(s, where), defaultDelay, 1);
        }
        if (!(entry instanceof Map<?, ?> map)) {
            throw new RulesException(where + ": each dlr entry must be like { DELIVRD: 80%, after: 3s }");
        }
        DeliveryReceipt.State state = null;
        boolean stateGiven = false;
        double weight = 1;
        Delay delay = defaultDelay;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (key.equals("after")) {
                delay = delay(e.getValue(), where);
                continue;
            }
            if (stateGiven) {
                throw new RulesException(where + ": dlr entry names two states: " + map.keySet());
            }
            stateGiven = true;
            state = state(key, where);
            weight = percent(e.getValue(), where);
        }
        if (!stateGiven) {
            throw new RulesException(where + ": dlr entry has no state: " + map);
        }
        return new Accept.Outcome(state, delay, weight);
    }

    private static DeliveryReceipt.State state(Object value, String where) {
        String s = String.valueOf(value).trim();
        if (s.equalsIgnoreCase("none") || s.equalsIgnoreCase("no") || s.equalsIgnoreCase("false")) {
            return null;
        }
        return DeliveryReceipt.State.of(s).orElseThrow(() -> new RulesException(where + ": unknown DLR state '"
                + s + "' (use DELIVRD, UNDELIV, EXPIRED, REJECTD, ACCEPTD, UNKNOWN, DELETED, ENROUTE or none)"));
    }

    private static double percent(Object value, String where) {
        if (value == null) {
            return 1;
        }
        if (value instanceof Number n) {
            return positive(n.doubleValue(), where);
        }
        String s = String.valueOf(value).trim();
        try {
            return positive(Double.parseDouble(s.endsWith("%") ? s.substring(0, s.length() - 1).trim() : s), where);
        } catch (NumberFormatException e) {
            throw new RulesException(where + ": '" + s + "' is not a percentage");
        }
    }

    private static double positive(double d, String where) {
        if (d <= 0) {
            throw new RulesException(where + ": percentages must be positive");
        }
        return d;
    }

    private static Delay delay(Object value, String where) {
        try {
            return Delay.parse(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new RulesException(where + ": " + e.getMessage());
        }
    }

    private static Reject reject(Object value, String where) {
        Object v = value instanceof Map<?, ?> map ? map.get("status") : value;
        if (v == null) {
            throw new RulesException(where + ": 'reject' needs an SMPP status such as ESME_RINVDSTADR or 0x0B");
        }
        int code;
        if (v instanceof Number n) {
            code = n.intValue();
        } else {
            String s = String.valueOf(v).trim();
            String upper = s.toUpperCase(Locale.ROOT);
            try {
                code = CommandStatus.valueOf(upper.startsWith("ESME_") ? upper : "ESME_" + upper).code();
            } catch (IllegalArgumentException notAName) {
                try {
                    code = upper.startsWith("0X") ? Integer.parseInt(upper.substring(2), 16) : Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    throw new RulesException(where + ": unknown status '" + s + "' (use a name like ESME_RMSGQFUL"
                            + " or a code like 0x14)");
                }
            }
        }
        if (code == 0) {
            throw new RulesException(where + ": reject status must not be ESME_ROK");
        }
        return new Reject(code);
    }

    /** Writes a rule set back as YAML in the same shape {@link #parse} reads. */
    public static String format(RuleSet set) {
        StringBuilder sb = new StringBuilder("rules:\n");
        for (Rule rule : set.rules()) {
            sb.append("  - ");
            if (!rule.name().startsWith("rule ")) {
                sb.append("name: ").append(quote(rule.name())).append("\n    ");
            }
            sb.append("match: ").append(rule.match().isAny() ? "{}" : formatMatch(rule.match())).append("\n    ");
            appendAction(sb, rule.action(), "    ");
        }
        sb.append("  - default:\n      ");
        appendAction(sb, set.defaultAction(), "      ");
        return sb.toString();
    }

    private static String formatMatch(Match m) {
        List<String> parts = new ArrayList<>();
        if (m.to() != null) {
            parts.add("to: " + quote(m.to().pattern()));
        }
        if (m.from() != null) {
            parts.add("from: " + quote(m.from().pattern()));
        }
        if (m.account() != null) {
            parts.add("account: " + quote(m.account().pattern()));
        }
        if (m.text() != null) {
            parts.add("text: " + quote(m.text().pattern()));
        }
        return "{ " + String.join(", ", parts) + " }";
    }

    private static void appendAction(StringBuilder sb, RuleAction action, String indent) {
        switch (action) {
            case Reject r -> sb.append("reject: ").append(CommandStatus.describe(r.commandStatus())).append("\n");
            case Accept a -> {
                if (a.outcomes().size() == 1) {
                    Accept.Outcome o = a.outcomes().getFirst();
                    sb.append("accept: { dlr: ").append(o.state() == null ? "none" : o.state().stat());
                    if (!o.delay().isZero()) {
                        sb.append(", after: ").append(o.delay());
                    }
                    sb.append(" }\n");
                } else {
                    sb.append("accept:\n").append(indent).append("  dlr:\n");
                    for (Accept.Outcome o : a.outcomes()) {
                        sb.append(indent).append("    - { ").append(o.state() == null ? "none" : o.state().stat())
                                .append(": ").append(formatWeight(o.weight())).append("%");
                        if (!o.delay().isZero()) {
                            sb.append(", after: ").append(o.delay());
                        }
                        sb.append(" }\n");
                    }
                }
            }
        }
    }

    private static String formatWeight(double w) {
        return w == Math.rint(w) ? String.valueOf((long) w) : String.valueOf(w);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
