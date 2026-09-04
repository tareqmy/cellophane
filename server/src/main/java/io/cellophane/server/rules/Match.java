package io.cellophane.server.rules;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Which submits a rule applies to. Every condition given must hold; a null condition matches anything.
 * {@code to}, {@code from} and {@code text} are regexes searched anywhere in the value (anchor with {@code ^} for a
 * prefix); {@code account} must match the whole system_id.
 */
public record Match(Pattern to, Pattern from, Pattern account, Pattern text) {

    public static final Match ANY = new Match(null, null, null, null);

    public boolean matches(RuleContext ctx) {
        return find(to, ctx.to()) && find(from, ctx.from()) && find(text, ctx.text())
                && (account == null || ctx.account() != null && account.matcher(ctx.account()).matches());
    }

    private static boolean find(Pattern p, String value) {
        return p == null || value != null && p.matcher(value).find();
    }

    public boolean isAny() {
        return to == null && from == null && account == null && text == null;
    }

    // Pattern compares by identity; two matches with the same regex sources are the same match.

    @Override
    public boolean equals(Object o) {
        return o instanceof Match m && same(to, m.to) && same(from, m.from) && same(account, m.account)
                && same(text, m.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source(to), source(from), source(account), source(text));
    }

    private static boolean same(Pattern a, Pattern b) {
        return Objects.equals(source(a), source(b)) && (a == null || a.flags() == b.flags());
    }

    private static String source(Pattern p) {
        return p == null ? null : p.pattern();
    }
}
