package io.cellophane.server.rules;

import java.time.Duration;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A fixed delay or a uniform range, written like {@code 2s}, {@code 500ms} or {@code 1s-5s}. */
public record Delay(Duration min, Duration max) {

    public static final Delay NONE = new Delay(Duration.ZERO, Duration.ZERO);

    private static final Pattern ONE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)");
    private static final Pattern RANGE = Pattern.compile("(.+?)\\s*(?:-|\\.\\.)\\s*(.+)");

    public Delay {
        if (min.isNegative() || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("invalid delay range " + min + " to " + max);
        }
    }

    public static Delay fixed(Duration d) {
        return new Delay(d, d);
    }

    /** Parses {@code 2s}, {@code 500ms}, {@code 1.5s}, {@code 1s-5s}, {@code 1s..5s} or a bare millisecond count. */
    public static Delay parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        Matcher range = RANGE.matcher(t);
        if (range.matches() && ONE.matcher(range.group(1).trim()).matches()) {
            return new Delay(one(range.group(1)), one(range.group(2)));
        }
        return fixed(one(t));
    }

    private static Duration one(String text) {
        String t = text.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            return Duration.ofMillis(Long.parseLong(t));
        }
        Matcher m = ONE.matcher(t);
        if (!m.matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a delay like 2s, 500ms, 1m or 1s-5s");
        }
        double n = Double.parseDouble(m.group(1));
        long millis = switch (m.group(2)) {
            case "ms" -> (long) n;
            case "s" -> (long) (n * 1_000);
            case "m" -> (long) (n * 60_000);
            default -> (long) (n * 3_600_000);
        };
        return Duration.ofMillis(millis);
    }

    public Duration pick(RandomGenerator random) {
        if (min.equals(max)) {
            return min;
        }
        long span = max.toMillis() - min.toMillis();
        return min.plusMillis(random.nextLong(span + 1));
    }

    public boolean isZero() {
        return max.isZero();
    }

    @Override
    public String toString() {
        return min.equals(max) ? human(min) : human(min) + "-" + human(max);
    }

    static String human(Duration d) {
        long ms = d.toMillis();
        if (ms % 3_600_000 == 0 && ms > 0) {
            return ms / 3_600_000 + "h";
        }
        if (ms % 60_000 == 0 && ms > 0) {
            return ms / 60_000 + "m";
        }
        if (ms % 1_000 == 0) {
            return ms / 1_000 + "s";
        }
        return ms + "ms";
    }
}
