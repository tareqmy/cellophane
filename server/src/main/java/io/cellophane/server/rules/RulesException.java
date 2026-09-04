package io.cellophane.server.rules;

/** A rules document could not be understood. The message says where and why. */
public final class RulesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RulesException(String message) {
        super(message);
    }

    public RulesException(String message, Throwable cause) {
        super(message, cause);
    }
}
