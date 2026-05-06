package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.anthropic.AnthropicTokenUsage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-call trace logging of LLM input and output. Renders a
 * {@link ChatRequest} / {@link ChatResponse} as readable text and
 * emits it under a dedicated logger ({@code de.mhus.vance.brain.ai.trace})
 * — turn TRACE on for that logger to see every prompt and reply.
 *
 * <p>Format is verbose by design: every message is dumped verbatim
 * (no truncation) so the operator can replay what the model saw.
 * If trace is off, formatting is skipped entirely.
 */
public final class AiTraceLogger {

    /** Logger name. Matches {@code logging.level.de.mhus.vance: TRACE} by
     *  prefix; configure individually if you want a finer dial. */
    private static final Logger LOG = LoggerFactory.getLogger(
            "de.mhus.vance.brain.ai.trace");

    private AiTraceLogger() {}

    public static boolean enabled() {
        return LOG.isTraceEnabled();
    }

    /** Logs an outgoing {@link ChatRequest}. No-op when trace is off. */
    public static void logRequest(String chatName, ChatRequest request) {
        if (!enabled() || request == null) return;
        LOG.trace(">>> [{}] request{}\n{}",
                chatName,
                renderTools(request),
                renderMessages(request.messages()));
    }

    /** Logs the completed {@link ChatResponse}. No-op when trace is off. */
    public static void logResponse(String chatName, ChatResponse response) {
        if (!enabled() || response == null) return;
        AiMessage ai = response.aiMessage();
        StringBuilder sb = new StringBuilder();
        if (ai == null) {
            sb.append("(no AI message)");
        } else {
            String text = ai.text();
            if (text != null && !text.isEmpty()) {
                sb.append("[ai/text]\n").append(text);
            }
            if (ai.hasToolExecutionRequests()) {
                if (sb.length() > 0) sb.append('\n');
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    sb.append("[ai/tool-call] id=").append(req.id())
                            .append(" name=").append(req.name())
                            .append("\n  args=").append(req.arguments())
                            .append('\n');
                }
            }
            if (sb.length() == 0) {
                sb.append("(empty AI message — no text, no tool calls)");
            }
        }
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            sb.append("\n[tokens] ").append(usage);
            if (usage instanceof AnthropicTokenUsage atu
                    && (atu.getCacheCreationInputTokens() > 0
                            || atu.getCacheReadInputTokens() > 0)) {
                sb.append(" cache_create=").append(atu.getCacheCreationInputTokens())
                        .append(" cache_read=").append(atu.getCacheReadInputTokens());
                Integer in = usage.inputTokenCount();
                long fullInput = (in == null ? 0 : in)
                        + atu.getCacheCreationInputTokens()
                        + atu.getCacheReadInputTokens();
                if (fullInput > 0) {
                    double hitRate = atu.getCacheReadInputTokens() * 100.0 / fullInput;
                    sb.append(String.format(" hit_rate=%.1f%%", hitRate));
                }
            }
        }
        if (response.finishReason() != null) {
            sb.append("\n[finish] ").append(response.finishReason());
        }
        LOG.trace("<<< [{}] response\n{}", chatName, sb);
    }

    /**
     * Logs the final aggregated AI message and any partial-token
     * preview from a streaming call. Streaming partials themselves
     * aren't logged — too noisy and reconstructible from the final
     * text. The full assembled text comes from the final
     * {@link ChatResponse}.
     */
    public static void logStreamingError(String chatName, Throwable error) {
        if (!enabled()) return;
        LOG.trace("!!! [{}] stream error: {}", chatName, error.toString());
    }

    // ──────────────────── formatting ────────────────────

    private static String renderMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "(no messages)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (i > 0) sb.append('\n');
            sb.append('[').append(roleOf(m)).append("] ");
            sb.append(textOf(m));
        }
        return sb.toString();
    }

    private static String renderTools(ChatRequest request) {
        List<ToolSpecification> tools = request.toolSpecifications();
        if (tools == null || tools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" (tools=");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(tools.get(i).name());
        }
        sb.append(')');
        return sb.toString();
    }

    private static String roleOf(ChatMessage m) {
        if (m instanceof SystemMessage) return "system";
        if (m instanceof UserMessage) return "user";
        if (m instanceof AiMessage) return "ai";
        if (m instanceof ToolExecutionResultMessage) return "tool-result";
        return m.getClass().getSimpleName();
    }

    private static String textOf(ChatMessage m) {
        if (m instanceof SystemMessage s) {
            return s.text();
        }
        if (m instanceof UserMessage u) {
            try {
                return u.singleText();
            } catch (RuntimeException e) {
                // Multimodal content — render a marker, keep going.
                return "(multimodal user message: " + u.contents() + ")";
            }
        }
        if (m instanceof AiMessage a) {
            StringBuilder sb = new StringBuilder();
            if (a.text() != null && !a.text().isEmpty()) {
                sb.append(a.text());
            }
            if (a.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : a.toolExecutionRequests()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append("  → tool-call id=").append(req.id())
                            .append(" name=").append(req.name())
                            .append(" args=").append(req.arguments());
                }
            }
            return sb.length() == 0 ? "(empty)" : sb.toString();
        }
        if (m instanceof ToolExecutionResultMessage t) {
            return "id=" + t.id()
                    + " name=" + t.toolName()
                    + "\n  result=" + t.text();
        }
        return m.toString();
    }
}
