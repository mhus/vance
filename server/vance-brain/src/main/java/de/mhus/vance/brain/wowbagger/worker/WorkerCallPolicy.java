package de.mhus.vance.brain.wowbagger.worker;

import de.mhus.vance.brain.ai.ModelAllowlist;
import de.mhus.vance.brain.script.ScriptCallPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The script worker's gate, one instance per chunk run: every
 * {@code vance.llm} / {@code vance.zem} call of the worker script must hit a
 * model on the operator allowlist ({@code wowbagger.allowed-models} — the
 * same gate the other workers pass before the run), and the chunk may make at
 * most {@code maxCallsPerRecord × records} provider calls. Without it a worker
 * script could call any model a million times and the allowlist would mean
 * nothing for script workers.
 */
final class WorkerCallPolicy implements ScriptCallPolicy {

    static final String ALLOWLIST_SETTING = "wowbagger.allowed-models";

    private final Function<String, String> llmModelOf;
    private final @Nullable String allowlist;
    private final int maxCalls;
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * @param llmModelOf resolves a LightLlm recipe name to its model
     *                   ({@code instance:model}) the way the call itself will
     * @param allowlist  the operator allowlist, {@code null} = nothing approved
     * @param maxCalls   provider-call budget for this chunk
     */
    WorkerCallPolicy(Function<String, String> llmModelOf, @Nullable String allowlist, int maxCalls) {
        this.llmModelOf = llmModelOf;
        this.allowlist = allowlist;
        this.maxCalls = maxCalls;
    }

    @Override
    public void beforeLlmCall(String recipeName) {
        count();
        approve(llmModelOf.apply(recipeName), "LightLlm recipe '" + recipeName + "'");
    }

    @Override
    public void beforeDecision(String resolvedModel) {
        count();
        approve(resolvedModel, "decision");
    }

    /** Provider calls made so far in this chunk. */
    int calls() {
        return calls.get();
    }

    private void count() {
        int made = calls.incrementAndGet();
        if (made > maxCalls) {
            throw new IllegalStateException("call budget exhausted — more than " + maxCalls
                    + " provider calls for this chunk (maxCallsPerRecord × records)");
        }
    }

    private void approve(String model, String what) {
        if (!ModelAllowlist.approved(model, allowlist)) {
            throw new IllegalStateException("model '" + model + "' (" + what
                    + ") is not approved for Wowbagger workers — setting " + ALLOWLIST_SETTING
                    + (allowlist == null ? " is not set (fail-closed)" : " is '" + allowlist + "'"));
        }
    }
}
