package org.andre.construx.modules.messaging.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Runs inbound work on an executor, one task at a time per sender and in submission order; different senders run in
 * parallel. A failing task is logged and the sender's next task still runs. With {@code Runnable::run} as the
 * executor every task finishes before {@link #submit} returns (tests).
 */
public class InboundQueue implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InboundQueue.class);

    private final Executor executor;
    private final Map<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public InboundQueue(Executor executor) {
        this.executor = executor;
    }

    public synchronized void submit(String sender, Runnable task) {
        CompletableFuture<Void> previous = tails.getOrDefault(sender, CompletableFuture.completedFuture(null));
        // A failed predecessor (e.g. rejected by the executor) must not stop this one.
        CompletableFuture<Void> next = previous.exceptionally(error -> null).thenRunAsync(() -> runSafely(task),
                executor);
        tails.put(sender, next);
        next.whenComplete((ignored, error) -> {
            if (error != null) {
                log.error("WhatsApp inbound task for a sender was not run", error);
            }
            tails.remove(sender, next);
        });
    }

    /** Stops taking new work when the executor is a pool this queue owns. */
    @Override
    public void close() {
        if (executor instanceof ExecutorService pool) {
            pool.shutdown();
        }
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("WhatsApp inbound message failed", e);
        }
    }
}
