package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.CallAttribution;

/**
 * Where the accounting decorator hands a measured call. Narrow on purpose:
 * the {@code ai} package must not depend on the ledger's shape, and the
 * decorator has to be testable against a fake.
 *
 * <p>Two arguments, two questions — <b>who</b> was that
 * ({@link CallAttribution}, known by the caller) and <b>what did it cost</b>
 * ({@link UsageMeasurement}, known by the decorator). Neither has to take
 * the other apart.
 *
 * <p>Implementations must never throw: a bookkeeping failure may not break
 * the turn it observes.
 */
public interface UsageSink {

    /** Book one attempt. */
    void onCall(CallAttribution attribution, UsageMeasurement measurement);

    /**
     * Sink that drops everything. For construction paths outside Spring
     * (tests, tools) that build a provider by hand and do not care about
     * accounting.
     */
    UsageSink NOOP = (attribution, measurement) -> { };
}
