package io.cellophane.server.rules;

import io.cellophane.smpp.receipt.DeliveryReceipt;

import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Accept the submit with {@code ESME_ROK}, then (if a receipt was requested) send one of the weighted outcomes.
 */
public record Accept(List<Outcome> outcomes) implements RuleAction {

    /**
     * One possible receipt.
     *
     * @param state  the delivery state to report, or null for no receipt at all
     * @param delay  how long after the submit the receipt is sent
     * @param weight relative probability among the outcomes of one rule
     */
    public record Outcome(DeliveryReceipt.State state, Delay delay, double weight) {

        public Outcome {
            if (weight <= 0) {
                throw new IllegalArgumentException("weight must be positive");
            }
            delay = delay == null ? Delay.NONE : delay;
        }
    }

    public Accept {
        outcomes = List.copyOf(outcomes);
        if (outcomes.isEmpty()) {
            throw new IllegalArgumentException("accept needs at least one outcome");
        }
    }

    /** Accept and report the given state after a delay. */
    public static Accept receipt(DeliveryReceipt.State state, Duration after) {
        return new Accept(List.of(new Outcome(state, Delay.fixed(after), 1)));
    }

    /** Accept and never send a receipt. */
    public static Accept silent() {
        return new Accept(List.of(new Outcome(null, Delay.NONE, 1)));
    }

    public Outcome pick(RandomGenerator random) {
        if (outcomes.size() == 1) {
            return outcomes.getFirst();
        }
        double total = outcomes.stream().mapToDouble(Outcome::weight).sum();
        double roll = random.nextDouble() * total;
        for (Outcome o : outcomes) {
            roll -= o.weight();
            if (roll < 0) {
                return o;
            }
        }
        return outcomes.getLast();
    }
}
