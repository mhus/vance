package de.mhus.vance.brain.ai.power;

import dev.langchain4j.exception.NonRetriableException;

/**
 * A model call refused before it reached the provider, because an admin has
 * switched LLM access off — for the whole tenant or for the user the call runs
 * for.
 *
 * <p>Non-retriable like a budget refusal, but unlike that one it does not let
 * the resilience layer advance to the next chain entry: the switch holds for
 * every model, free local ones included, so the first refusal is already the
 * answer. The message is what the user reads.
 */
public class LlmPowerOffException extends NonRetriableException {

    public LlmPowerOffException(String message) {
        super(message);
    }
}
