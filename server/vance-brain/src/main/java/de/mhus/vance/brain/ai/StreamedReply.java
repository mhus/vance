package de.mhus.vance.brain.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.jspecify.annotations.Nullable;

/**
 * One streamed LLM iteration's outcome: the assistant message plus the
 * wire-level facts an <em>empty</em> reply has to be diagnosed with — why
 * the model stopped, and against which output cap it was running.
 *
 * <p>Engines used to reduce a completion to its {@link AiMessage} right
 * after the stream finished, which threw the finish reason away. That made
 * two very different failures indistinguishable at the point where the
 * user-facing message is worded:
 *
 * <ul>
 *   <li><b>Provider glitch / model collapse</b> — empty with any finish
 *       reason but {@link FinishReason#LENGTH}. Transient; retrying or
 *       switching models is the right advice.</li>
 *   <li><b>Output cap exhausted</b> — empty with {@code LENGTH}. The model
 *       spent its whole {@code max_tokens} budget before emitting anything
 *       visible; on reasoning models the {@code reasoning_content} pass
 *       does that on its own. Deterministic: an identical re-request hits
 *       the identical wall, so "try again" is actively misleading advice.
 *       {@link ResilientStreamingChatModel} skips its retry budget for
 *       this case for the same reason.</li>
 * </ul>
 *
 * @param message         the model's reply (may carry neither text nor tool
 *                        calls when the model collapsed or was truncated)
 * @param finishReason    provider-reported stop reason, {@code null} when
 *                        the provider omitted it
 * @param maxOutputTokens the cap the request carried, {@code null} when the
 *                        request left it unset
 */
public record StreamedReply(
        AiMessage message,
        @Nullable FinishReason finishReason,
        @Nullable Integer maxOutputTokens) {

    /**
     * Captures a finished stream. Reads the cap off the request rather than
     * the model config so the value quoted to the user is the one that
     * actually went on the wire.
     */
    public static StreamedReply of(ChatResponse response, ChatRequest request) {
        return new StreamedReply(
                response.aiMessage(),
                response.finishReason(),
                request.parameters() == null ? null : request.parameters().maxOutputTokens());
    }

    /** True when the reply carries neither text nor a tool call. */
    public boolean isEmpty() {
        if (message.hasToolExecutionRequests()) return false;
        String text = message.text();
        return text == null || text.isBlank();
    }

    /**
     * True when the model was cut off by the output-token cap rather than
     * ending on its own. Only meaningful together with {@link #isEmpty()} —
     * a truncated reply that <em>did</em> produce text is a different
     * (non-fatal) situation the engines handle as normal output.
     */
    public boolean atOutputCap() {
        return finishReason == FinishReason.LENGTH;
    }

    /**
     * Assistant text to surface for an empty reply. The output-cap case is
     * worded here because the diagnosis and the actionable knobs are the
     * same everywhere; every other cause keeps the caller's own wording,
     * which differs per engine (how it parks the process, whether a
     * rephrase helps).
     *
     * @param collapseMessage engine-specific text for a non-truncation
     *                        empty reply — a provider glitch or collapse
     * @param stateNote       what the engine did with the process, appended
     *                        to the truncation text so the user knows where
     *                        the turn left off ({@code null} to omit)
     */
    public String emptyReplyMessage(String collapseMessage, @Nullable String stateNote) {
        if (!atOutputCap()) {
            return collapseMessage;
        }
        String cap = maxOutputTokens == null
                ? "its output-token limit"
                : "its output-token limit of " + maxOutputTokens;
        return "_The model reached " + cap + " before producing any answer or tool "
                + "call — on a reasoning model the thinking pass can consume the whole "
                + "budget. Retrying unchanged reproduces this. Raise `maxTokens` in the "
                + "recipe (or `defaultMaxOutputTokens` for the model), lower the "
                + "reasoning effort, or switch the model."
                + (stateNote == null || stateNote.isBlank() ? "" : " " + stateNote)
                + "_";
    }
}
