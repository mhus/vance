package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Fired when the resilient retry layer gives up on empty completions:
 * every attempt (and, in a fallback chain, every entry) returned a
 * response with neither text nor a tool call, and the empty response is
 * about to be delivered to the engine.
 *
 * <p>The purpose is post-mortem evidence collection, not control flow:
 * the sink receives the complete {@link ChatRequest} — messages <i>and</i>
 * the {@code tools} array — which is exactly what the empty-response
 * diagnosis needs (a model that names a tool absent from the offered set
 * is the leading suspect; the tool name never arrives as data, so the
 * only evidence is the request text itself).
 *
 * <p>Fired at most once per logical chat call — the resilient layer wraps
 * itself twice in chained setups (inner per-model, outer across models)
 * and both layers share one guarded sink built by
 * {@link #once(EmptyResponseDiagnosticSink)}. Empty completions at the
 * output-token cap ({@code finish=LENGTH}) are deterministic walls, not
 * evidence, and are excluded from the attempt count; genuine empties
 * observed earlier in the chain still fire when the call ends on a
 * cap wall, so they stay countable.
 *
 * <p>Only chats spawned through {@code EngineChatFactory} get the
 * default sink — callers that build {@code AiChatOptions} themselves
 * (light LLM calls, memory compaction, FIM) carry {@code null} and
 * report nothing. That is the intended scope: engine turns are where
 * the empty-budget chain exists.
 */
public interface EmptyResponseDiagnosticSink {

    /**
     * Reports one exhausted chain of empty completions.
     *
     * @param request    the request as last issued — messages and
     *                   {@code tools} array for the candidate diff
     * @param modelLabel label of the chain entry that produced the
     *                   last evidence-bearing (non-cap) empty
     *                   response — may differ from the entry whose
     *                   cap wall ended the call
     * @param attempts   empty attempts across the chain, excluding
     *                   output-cap walls (they are deterministic and
     *                   not evidence)
     */
    void onEmptyResponseExhausted(ChatRequest request, String modelLabel, int attempts);

    /**
     * Wraps {@code delegate} so the first invocation wins and all later
     * ones are swallowed. Used at the composition point
     * ({@code AiModelService.createChat}) so a fallback chain fires at
     * most once per call, no matter which inner layer exhausts first.
     */
    static EmptyResponseDiagnosticSink once(EmptyResponseDiagnosticSink delegate) {
        return new EmptyResponseDiagnosticSink() {
            private final AtomicBoolean fired = new AtomicBoolean();

            @Override
            public void onEmptyResponseExhausted(ChatRequest request, String modelLabel, int attempts) {
                if (fired.compareAndSet(false, true)) {
                    delegate.onEmptyResponseExhausted(request, modelLabel, attempts);
                }
            }
        };
    }

    /** Convenience for {@code null}-safe sink invocation. */
    static void fire(@Nullable EmptyResponseDiagnosticSink sink, ChatRequest request, String modelLabel, int attempts) {
        if (sink != null) {
            sink.onEmptyResponseExhausted(request, modelLabel, attempts);
        }
    }
}
