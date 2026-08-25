/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// [BatchExchange#abort()] — the teardown signal, and the one thing it has to do that
/// [BatchExchange#finish()] does not: get a drain thread that is *already* blocked inside the
/// exchange moving again, rather than leaving it to time out.
///
/// `finish()` only sets a flag, which a blocked producer notices when its 10 ms timed queue
/// operation expires. `abort()` has to also make that operation succeed. Which operation it is
/// depends on the mode, and this is the whole reason both are tested here:
///
/// - **Recycling** — the drain blocks in [BatchExchange#takeBatch()], on a timed poll of an
///   empty free pool. Freeing ready-queue capacity does nothing for it; the pool has to become
///   non-empty, which `abort()` achieves by returning the leftovers on the ready queue to it.
/// - **Detaching** — there is no pool, so the drain blocks in
///   [BatchExchange#publish(Object)], on a timed offer to a full ready queue. Here clearing
///   the ready queue is what unblocks it.
///
/// The first two tests below are the discriminating ones, and they are deterministic — no
/// sleeps, no wall-clock bound, no threads. They drive the exchange by hand into exactly the
/// state a drain blocks in, call `abort()`, and then assert that the operation the drain is
/// blocked in *now succeeds*: `takeBatch()` hands back a holder instead of null, `publish()`
/// returns true instead of false. Those are the outcomes only reachable if the queues were
/// actually rearranged; a bare flag write leaves the opposite result in each case.
///
/// The two thread-based tests that follow check the liveness property end to end — a genuinely
/// parked producer does get released. They deliberately do *not* assert a tight release
/// latency: the 10 ms timeout is also an exit path, so a "released within N ms" bound
/// discriminates only statistically and would be flaky in both directions. Latency is measured
/// where it is actually observable, in [dev.hardwood.ReaderCloseLatencyTest].
class BatchExchangeAbortTest {

    /// Long enough that a producer which is genuinely blocked indefinitely cannot be mistaken
    /// for one that is about to return: both blocking paths loop on a 10 ms timeout while
    /// `finished` is false, so any exit inside this window would have to come from `abort()`.
    private static final long STILL_BLOCKED_MILLIS = 100;

    /// Recycling mode: the leftovers must come back to the free pool, because that is the queue
    /// the drain is waiting on.
    @Test
    void abortReturnsRecyclingLeftoversToTheFreePool() throws Exception {
        BatchExchange<Object> exchange = BatchExchange.recycling("c0", Object::new);

        // Reproduce the state a mid-file drain is in: it has taken all three pre-allocated
        // holders, two of them are sitting on the (capacity-2) ready queue unconsumed, and the
        // third is the batch it is filling. The free pool is empty, so its next takeBatch()
        // blocks.
        Object first = exchange.takeBatch();
        Object second = exchange.takeBatch();
        Object inHand = exchange.takeBatch();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(inHand).isNotNull();
        assertThat(exchange.publish(first)).as("first publish, ready queue has room").isTrue();
        assertThat(exchange.publish(second)).as("second publish, ready queue now full").isTrue();

        exchange.abort();

        assertThat(exchange.isFinished()).as("abort() implies finish()").isTrue();
        assertThat(exchange.takeBatch())
                .as("abort() must hand the ready-queue leftovers back to the free pool, so the "
                        + "takeBatch() the drain is blocked in completes with a holder instead "
                        + "of timing out to null")
                .isNotNull();
        assertThat(exchange.takeBatch())
                .as("both leftovers are recycled, not just one")
                .isNotNull();
        assertThat(exchange.poll())
                .as("and the ready queue is left empty — an aborted exchange publishes nothing")
                .isNull();
    }

    /// Detaching mode: there is no pool to refill, so the ready queue has to lose its contents
    /// for the drain's blocked offer to fit.
    @Test
    void abortClearsTheDetachingReadyQueue() throws Exception {
        BatchExchange<Object> exchange = BatchExchange.detaching("c0", Object::new);

        assertThat(exchange.publish(exchange.takeBatch())).isTrue();
        assertThat(exchange.publish(exchange.takeBatch())).isTrue();
        // Ready queue is now full at capacity 2; the drain's next publish() blocks.

        exchange.abort();

        assertThat(exchange.isFinished()).as("abort() implies finish()").isTrue();
        assertThat(exchange.publish(exchange.takeBatch()))
                .as("abort() must free ready-queue capacity, so the offer the drain is blocked "
                        + "in succeeds (true) rather than timing out and reporting the exchange "
                        + "finished (false)")
                .isTrue();
    }

    /// Liveness, recycling mode: a producer actually parked in `takeBatch()` is released.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void abortReleasesAProducerParkedInTakeBatch() throws Exception {
        BatchExchange<Object> exchange = BatchExchange.recycling("c0", Object::new);
        Object first = exchange.takeBatch();
        Object second = exchange.takeBatch();
        exchange.takeBatch();
        exchange.publish(first);
        exchange.publish(second);

        assertReleasedByAbort(exchange, exchange::takeBatch);
    }

    /// Liveness, detaching mode: a producer actually parked in `publish()` is released.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void abortReleasesAProducerParkedInPublish() throws Exception {
        BatchExchange<Object> exchange = BatchExchange.detaching("c0", Object::new);
        exchange.publish(exchange.takeBatch());
        exchange.publish(exchange.takeBatch());

        assertReleasedByAbort(exchange, () -> exchange.publish(new Object()));
    }

    /// Runs `blockingCall` on a virtual thread — the drain threads are virtual too — checks it
    /// is still blocked after [#STILL_BLOCKED_MILLIS], then aborts and requires it to return.
    ///
    /// The pre-abort check is what makes the post-abort return meaningful: without it, a call
    /// that never blocked at all would pass.
    private static void assertReleasedByAbort(
            BatchExchange<Object> exchange, BlockingCall blockingCall) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread producer = Thread.ofVirtual().start(() -> {
            entered.countDown();
            try {
                blockingCall.call();
            }
            catch (Throwable t) {
                failure.set(t);
            }
            finally {
                returned.countDown();
            }
        });

        assertThat(entered.await(5, TimeUnit.SECONDS)).as("producer started").isTrue();
        assertThat(returned.await(STILL_BLOCKED_MILLIS, TimeUnit.MILLISECONDS))
                .as("producer must still be blocked in the exchange before abort() — otherwise "
                        + "this test proves nothing about abort()")
                .isFalse();

        exchange.abort();

        assertThat(returned.await(5, TimeUnit.SECONDS))
                .as("abort() must release the parked producer")
                .isTrue();
        producer.join();
        assertThat(failure.get()).as("producer failed").isNull();
    }

    @FunctionalInterface
    private interface BlockingCall {
        void call() throws InterruptedException;
    }
}
