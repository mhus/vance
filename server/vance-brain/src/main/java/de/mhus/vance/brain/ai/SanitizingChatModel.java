package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.parser.MessageParser;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;

/**
 * Decorating {@link ChatModel} that runs two model-aware post-
 * processors over the raw response before handing it back to the
 * engine layer:
 *
 * <ol>
 *   <li><b>Think-tag strip</b> ({@link LlmResponseSanitizer}, gated by
 *       {@code stripThinkTags}) — removes reasoning-mode markup so the
 *       chat UI doesn't show internal monologue.</li>
 *   <li><b>Message parser</b> ({@link MessageParser}, optional) —
 *       rewrites idiosyncratic outputs (Gemma-4 inline tool-call
 *       text, DeepSeek-V4 trailing-garbage JSON) into the structured
 *       shape the engines expect.</li>
 * </ol>
 *
 * <p>The decorator MUST be stacked OUTSIDE {@link LoggingChatModel} so
 * the trace recorder + the per-call stats logger see the verbatim
 * response (forensic value), while everything above this layer —
 * engines, chat-message persistence, judges — sees only the cleaned
 * answer.
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
    private final boolean stripEnabled;
    private final @Nullable MessageParser messageParser;

    public SanitizingChatModel(
            ChatModel delegate,
            LlmResponseSanitizer sanitizer,
            boolean stripEnabled,
            @Nullable MessageParser messageParser) {
        this.delegate = delegate;
        this.sanitizer = sanitizer;
        this.stripEnabled = stripEnabled;
        this.messageParser = messageParser;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatResponse raw = delegate.chat(request);
        if (raw == null || raw.aiMessage() == null) {
            return raw;
        }
        // Stage 1 — message parser. Runs first so the synthesized
        // tool_calls (Gemma-4) or repaired tool args (DeepSeek-V4) are
        // visible to the think-tag stripper if it needs to look at text.
        ChatResponse afterParse = messageParser == null
                ? raw
                : messageParser.parse(raw);
        if (afterParse == null) afterParse = raw;
        // Stage 2 — think-tag strip on the text payload.
        if (!stripEnabled) {
            return afterParse;
        }
        AiMessage parsedAi = afterParse.aiMessage();
        if (parsedAi == null) return afterParse;
        AiMessage clonedAi = sanitize(parsedAi);
        if (clonedAi == parsedAi) {
            // No-op strip (no markup present) — return upstream result
            // to avoid an unnecessary allocation.
            return afterParse;
        }
        ChatResponse.Builder b = ChatResponse.builder().aiMessage(clonedAi);
        // metadata() already contains tokenUsage + finishReason; the
        // builder rejects setting both groups ("Cannot set both
        // 'metadata' and 'tokenUsage'"). Prefer metadata; the discrete
        // setters are only the fallback when no metadata was provided.
        if (afterParse.metadata() != null) {
            b.metadata(afterParse.metadata());
        } else {
            if (afterParse.tokenUsage() != null) b.tokenUsage(afterParse.tokenUsage());
            if (afterParse.finishReason() != null) b.finishReason(afterParse.finishReason());
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
