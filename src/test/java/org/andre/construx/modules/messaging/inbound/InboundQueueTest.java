package org.andre.construx.modules.messaging.inbound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InboundQueueTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final InboundQueue queue = new InboundQueue(pool);
    private final List<String> done = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    void oneSendersMessagesRunInArrivalOrderWhileOtherSendersGoAhead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);

        queue.submit("521000000001", () -> {
            await(release);
            done.add("a1");
        });
        queue.submit("521000000001", () -> done.add("a2"));
        queue.submit("521000000001", () -> done.add("a3"));
        queue.submit("521000000002", () -> {
            done.add("b1");
            otherDone.countDown();
        });

        assertThat(otherDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(done).containsExactly("b1");

        release.countDown();
        waitForSize(4);
        assertThat(done).containsExactly("b1", "a1", "a2", "a3");
    }

    @Test
    void aFailingMessageDoesNotStopTheNextOneFromTheSameSender() throws Exception {
        queue.submit("521000000003", () -> {
            throw new IllegalStateException("boom");
        });
        queue.submit("521000000003", () -> done.add("after"));

        waitForSize(1);
        assertThat(done).containsExactly("after");
    }

    @Test
    void aSynchronousQueueRunsEachMessageBeforeSubmitReturns() {
        InboundQueue inline = new InboundQueue(Runnable::run);

        inline.submit("521000000004", () -> done.add("now"));

        assertThat(done).containsExactly("now");
    }

    private void waitForSize(int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (done.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
