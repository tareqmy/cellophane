package io.cellophane.server.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** An ESME account that may bind to the fake operator. */
public record Account(String systemId, String password, int windowSize) {

    public static final int DEFAULT_WINDOW_SIZE = 10;

    public Account {
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(password, "password");
        if (systemId.isBlank()) {
            throw new IllegalArgumentException("system_id must not be blank");
        }
        if (windowSize < 1) {
            throw new IllegalArgumentException("window size must be at least 1");
        }
    }

    /** Parses {@code system_id:password[:window]}. */
    public static Account parse(String spec) {
        String[] parts = spec.trim().split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("account '" + spec + "' must be system_id:password[:window]");
        }
        int window = DEFAULT_WINDOW_SIZE;
        if (parts.length == 3) {
            try {
                window = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("account '" + spec + "' has a non-numeric window size", e);
            }
        }
        return new Account(parts[0].trim(), parts[1], window);
    }

    /** Parses a comma-separated list of account specs, ignoring empty entries. */
    public static List<Account> parseAll(String specs) {
        List<Account> accounts = new ArrayList<>();
        for (String spec : specs.split(",")) {
            if (!spec.isBlank()) {
                accounts.add(parse(spec));
            }
        }
        return accounts;
    }
}
