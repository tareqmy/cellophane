package io.cellophane.server.rules;

/**
 * Drop the connection after the response to the {@code after}-th matching submit on a session, the way an operator
 * whose link is flapping does. Counting is per session and per rule; a fresh bind starts again.
 */
public record Disconnect(int after) implements RuleAction {

    public Disconnect {
        if (after < 1) {
            throw new IllegalArgumentException("after must be at least 1");
        }
    }
}
