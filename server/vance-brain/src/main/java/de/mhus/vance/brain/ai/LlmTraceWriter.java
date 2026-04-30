package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Optional per-call hook the {@link LoggingChatModel} /
 * {@link LoggingStreamingChatModel} wrappers fire after every
 * round-trip — gives the engine call-site a chance to persist the
 * raw input/output for tenants that opted into LLM-trace storage
 * ({@code tracing.llm} setting). Receives the same data
 * {@link AiTraceLogger} renders for the trace log.
 *
 * <p>{@link AiChatOptions#llmTraceWriter} is set by engines that want
 * persistence (typically as a Lambda closing over their {@code process},
 * {@code engineName} and {@code turnId}). When unset, the wrappers
 * skip the call — log-level tracing remains independent.
 *
 * <p>Implementations must be exception-safe: a thrown error from the
 * writer must not break the LLM call. The wrappers swallow throwables
 * from this hook.
 */
@FunctionalInterface
public interface LlmTraceWriter {

    /**
     * Called once per completed round-trip. {@code response} is the
     * fully-assembled reply (text + tool-call requests + token usage)
     * regardless of whether the underlying call streamed or was
     * synchronous.
     *
     * @param request   the request as sent to the provider
     * @param response  the assembled reply, or {@code null} on error
     * @param elapsedMs wall-clock duration of the call in milliseconds
     */
    void onRoundtrip(ChatRequest request, ChatResponse response, long elapsedMs);
}
