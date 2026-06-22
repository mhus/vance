package de.mhus.vance.brain.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;

/**
 * Decorating {@link ChatModel} that strips reasoning-mode markup
 * from the model's text response before handing the result to its
 * caller. The decorator MUST be stacked OUTSIDE
 * {@link LoggingChatModel} so the trace recorder + the per-call
 * stats logger see the verbatim response (forensic value), while
 * everything above this layer — engines, chat-message persistence,
 * judges — sees only the cleaned answer.
 *
 * <p>Layered shape (StandardAiChat builds it):
 *
 * <pre>
 *   engine
 *     ↑ cleaned ChatResponse
 *   SanitizingChatModel   ←   this class
 *     ↑ raw ChatResponse
 *   LoggingChatModel      ←   trace + stats (sees raw)
 *     ↑ raw ChatResponse
 *   provider ChatModel    ←   Anthropic / Gemini / OpenAI / Ollama
 * </pre>
 *
 * <p>When the active model's {@link ModelInfo#stripThinkTags()} is
 * {@code false} the decorator should not be inserted at all — callers
 * skip the wrap in that case. The {@code enabled} flag here is a
 * defensive fallback so a stray instantiation never falsely strips.
 *
 * <p>Tool-call passthrough: when the LLM responds with a
 * {@code ToolExecutionRequest} (no text or sparse text), the
 * sanitizer rebuilds the {@link AiMessage} with the same tool
 * requests and the cleaned text — never drops tool-call info.
 */
public class SanitizingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LlmResponseSanitizer sanitizer;
    private final boolean enabled;

    public SanitizingChatModel(ChatModel delegate, LlmResponseSanitizer sanitizer, boolean enabled) {
        this.delegate = delegate;
        this.sanitizer = sanitizer;
        this.enabled = enabled;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatResponse raw = delegate.chat(request);
        if (!enabled || raw == null || raw.aiMessage() == null) {
            return raw;
        }
        AiMessage clonedAi = sanitize(raw.aiMessage());
        if (clonedAi == raw.aiMessage()) {
            // No-op strip (no markup present) — return original to
            // avoid an unnecessary allocation.
            return raw;
        }
        ChatResponse.Builder b = ChatResponse.builder().aiMessage(clonedAi);
        // metadata() already contains tokenUsage + finishReason; the
        // builder rejects setting both groups ("Cannot set both
        // 'metadata' and 'tokenUsage'"). Prefer metadata; the discrete
        // setters are only the fallback when no metadata was provided.
        if (raw.metadata() != null) {
            b.metadata(raw.metadata());
        } else {
            if (raw.tokenUsage() != null) b.tokenUsage(raw.tokenUsage());
            if (raw.finishReason() != null) b.finishReason(raw.finishReason());
        }
        return b.build();
    }

    private AiMessage sanitize(AiMessage original) {
        String text = original.text();
        String cleaned = sanitizer.stripUnconditional(text == null ? "" : text);
        if (textEquals(text, cleaned)) {
            return original;
        }
        // Preserve tool-execution requests; AiMessage.from(...) variants
        // cover the three shapes: text-only, tool-only, mixed.
        if (original.hasToolExecutionRequests()) {
            return AiMessage.from(cleaned, original.toolExecutionRequests());
        }
        return AiMessage.from(cleaned);
    }

    private static boolean textEquals(@Nullable String a, @Nullable String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}
