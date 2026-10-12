package de.mhus.vance.brain.zem;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects usage of many decisions for one aggregated ledger booking —
 * the bulk path. Booking every decision as its own ledger write would put
 * one detail row per record into Mongo; a bulk caller instead passes an
 * accumulator to
 * {@link ZemService#decide(ZemPreparedCall, Object, ZemUsageAccumulator)}
 * and calls {@link ZemService#flush} at its own commit points (e.g. per
 * published chunk).
 *
 * <p>Thread-safe: worker threads add concurrently, the flush drains
 * atomically per counter. A flush that races an add books that add in the
 * next flush, never twice and never not at all.
 */
public final class ZemUsageAccumulator {

    private final ZemPreparedCall call;
    private final AtomicInteger decisions = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong durationMs = new AtomicLong();
    private final AtomicInteger failedCalls = new AtomicInteger();
    private final AtomicLong failedDurationMs = new AtomicLong();

    ZemUsageAccumulator(ZemPreparedCall call) {
        this.call = call;
    }

    ZemPreparedCall call() {
        return call;
    }

    void addSuccess(int decisionCount, long tokens, long millis) {
        decisions.addAndGet(decisionCount);
        inputTokens.addAndGet(tokens);
        durationMs.addAndGet(millis);
    }

    void addFailure(long millis) {
        failedCalls.incrementAndGet();
        failedDurationMs.addAndGet(millis);
    }

    /** Drain the counters for one booking. */
    Snapshot drain() {
        return new Snapshot(
                decisions.getAndSet(0),
                inputTokens.getAndSet(0),
                durationMs.getAndSet(0),
                failedCalls.getAndSet(0),
                failedDurationMs.getAndSet(0));
    }

    /** Decisions booked so far and not yet flushed — for progress displays. */
    public int pendingDecisions() {
        return decisions.get();
    }

    record Snapshot(int decisions, long inputTokens, long durationMs, int failedCalls, long failedDurationMs) {}
}
