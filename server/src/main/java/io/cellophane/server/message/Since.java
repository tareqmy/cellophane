package io.cellophane.server.message;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the {@code since} query parameter: an ISO-8601 instant or a relative age such as {@code 30s}, {@code 5m}. */
public final class Since {

    private static final Pattern RELATIVE = Pattern.compile("(\\d+)\\s*([smhd])");

    private Since() {
    }

    public static Instant parse(String value, Clock clock) {
        String v = value.trim();
        Matcher m = RELATIVE.matcher(v);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            Duration age = switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
            return clock.instant().minus(age);
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("since must be an ISO-8601 instant or a relative age like 30s, 5m, 2h, 1d");
        }
    }
}
