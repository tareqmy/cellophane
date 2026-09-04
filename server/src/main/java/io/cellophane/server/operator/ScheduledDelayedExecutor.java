package io.cellophane.server.operator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Production timer: a single daemon thread that never lets one failing task kill the others. */
public final class ScheduledDelayedExecutor implements DelayedExecutor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduledDelayedExecutor.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cellophane-timer");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void schedule(Duration delay, Runnable task) {
        executor.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("scheduled task failed", e);
            }
        }, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
