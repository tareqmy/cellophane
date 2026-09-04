package io.cellophane.server.operator;

import java.time.Duration;

/** Runs a task after a delay; the operator's clock for receipts and (later) latency injection. */
public interface DelayedExecutor {

    void schedule(Duration delay, Runnable task);
}
