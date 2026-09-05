package io.cellophane.server.rules;

import java.util.Objects;

/** Hold the submit_sm_resp back for a while; the following rules still decide what the answer is. */
public record Latency(Delay delay) implements RuleAction {

    public Latency {
        Objects.requireNonNull(delay, "delay");
    }
}
