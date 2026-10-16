package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.CallAttribution;

/**
 * Asked before every wire attempt whether the call may go out at all — the
 * counterpart of {@link UsageSink}, which hears about it afterwards. Narrow
 * for the same reasons: the {@code ai} package must not know how budgets are
 * stored, and the decorators have to be testable against a fake.
 *
 * <p>A refusal is an exception, not a return value, because it has to travel
 * through langchain4j's model interface unchanged: the resilience layer reads
 * it as non-retriable and may advance the fallback chain to a model the gate
 * lets through.
 */
public interface UsageGate {

    /**
     * Admit one attempt or refuse it.
     *
     * @throws LlmBudgetExceededException when a budget forbids the call
     */
    void admit(CallAttribution attribution, ModelInfo model);

    /** Gate that admits everything — for construction paths outside Spring. */
    UsageGate OPEN = (attribution, model) -> {};
}
