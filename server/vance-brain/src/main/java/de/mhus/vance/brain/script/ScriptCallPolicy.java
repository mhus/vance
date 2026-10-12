package de.mhus.vance.brain.script;

/**
 * A caller-supplied check that runs inside {@code vance.llm} and
 * {@code vance.zem} before every provider call a script makes. Absent for
 * ordinary scripts; the Wowbagger script worker sets one so a worker script
 * cannot reach a model the operator has not approved and cannot exceed its
 * call budget.
 *
 * <p>Implementations throw {@link IllegalStateException} to refuse — the
 * script API turns that into a script error with the reason, and the call
 * is not made.
 */
public interface ScriptCallPolicy {

    /** Before a {@code vance.llm.*} call with this LightLlm recipe. */
    void beforeLlmCall(String recipeName);

    /** Before a {@code vance.zem.decide} call against this resolved decision model ({@code instance:model}). */
    void beforeDecision(String resolvedModel);
}
