package io.cellophane.server.operator;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Counters for the stats endpoint, including a short sliding window for submits per second. */
public final class Metrics {

    private static final int WINDOW_SECONDS = 10;

    private final Clock clock;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong receiptsSent = new AtomicLong();
    private final AtomicLong moSent = new AtomicLong();
    private final AtomicLongArray perSecond = new AtomicLongArray(WINDOW_SECONDS);
    private final AtomicLongArray perSecondStamp = new AtomicLongArray(WINDOW_SECONDS);

    public Metrics(Clock clock) {
        this.clock = clock;
    }

    public void submitted(boolean wasAccepted) {
        submitted.incrementAndGet();
        (wasAccepted ? accepted : rejected).incrementAndGet();
        long second = clock.instant().getEpochSecond();
        int slot = Math.floorMod(second, WINDOW_SECONDS);
        if (perSecondStamp.get(slot) != second) {
            perSecondStamp.set(slot, second);
            perSecond.set(slot, 0);
        }
        perSecond.incrementAndGet(slot);
    }

    public void receiptSent() {
        receiptsSent.incrementAndGet();
    }

    public void moSent() {
        moSent.incrementAndGet();
    }

    /** Average submits per second over the last ten seconds. */
    public double tps() {
        long now = clock.instant().getEpochSecond();
        long total = 0;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            if (now - perSecondStamp.get(i) < WINDOW_SECONDS) {
                total += perSecond.get(i);
            }
        }
        return total / (double) WINDOW_SECONDS;
    }

    public Snapshot snapshot() {
        return new Snapshot(submitted.get(), accepted.get(), rejected.get(), receiptsSent.get(), moSent.get(),
                Math.round(tps() * 100) / 100.0);
    }

    public record Snapshot(long submitted, long accepted, long rejected, long receiptsSent, long moSent, double tps) {
    }
}
