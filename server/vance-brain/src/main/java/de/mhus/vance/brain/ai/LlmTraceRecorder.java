package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.anthropic.AnthropicTokenUsage;
import de.mhus.vance.shared.llmtrace.LlmTraceDirection;
import de.mhus.vance.shared.llmtrace.LlmTraceDocument;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits a langchain4j {@link ChatRequest}/{@link ChatResponse} pair
 * into per-leg {@link LlmTraceDocument} rows and writes them via
 * {@link LlmTraceService}. Engines build a {@link LlmTraceWriter}
 * Lambda that calls into here, closing over their {@code process},
 * {@code engineName} and a fresh {@code turnId}.
 *
 * <p>Layout per round-trip:
 * <ul>
 *   <li>One {@link LlmTraceDirection#INPUT} row per system / user
 *       message in the request.</li>
 *   <li>One {@link LlmTraceDirection#TOOL_RESULT} row per tool-result
 *       message in the request (i.e. results that fed back into this
 *       call).</li>
 *   <li>One {@link LlmTraceDirection#OUTPUT} row for the assistant's
 *       reply text — also carries token-usage and elapsed-ms.</li>
 *   <li>One {@link LlmTraceDirection#TOOL_CALL} row per tool-execution
 *       request the assistant emitted.</li>
 * </ul>
 *
 * <p>All rows of one round-trip share the same {@code turnId} and are
 * sequenced by {@code sequence} so the UI can render them in order.
 * Failures from {@link LlmTraceService#record} are swallowed inside
 * the service — callers don't need to wrap.
 */
public final class LlmTraceRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(LlmTraceRecorder.class);

    private LlmTraceRecorder() {}

    /**
     * Persist one full round-trip. Safe to call with {@code response ==
     * null} (e.g. on streaming error) — only the input messages are
     * recorded in that case.
     *
     * <p>A fresh {@code turnId} is minted per call, so the same engine
     * Lambda can be reused for every round-trip in a tool-loop without
     * accidentally collapsing them into one row group.
     */
    public static void record(
            LlmTraceService service,
            ThinkProcessDocument process,
            String engineName,
            ChatRequest request,
            @Nullable ChatResponse response,
            long elapsedMs) {
        if (service == null || process == null || request == null) {
            return;
        }
        String turnId = UUID.randomUUID().toString();
        try {
            int seq = 0;
            for (ChatMessage msg : safeMessages(request)) {
                seq = recordRequestMessage(service, process, engineName, turnId, seq, msg);
            }
            if (response != null) {
                seq = recordResponse(service, process, engineName, turnId, seq, response, elapsedMs);
            }
        } catch (RuntimeException e) {
            LOG.warn("LlmTraceRecorder.record failed for process='{}' turn='{}': {}",
                    process.getId(), turnId, e.toString());
        }
    }

    private static List<ChatMessage> safeMessages(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        return messages == null ? List.of() : messages;
    }

    private static int recordRequestMessage(
            LlmTraceService service,
            ThinkProcessDocument process,
            String engineName,
            String turnId,
            int seq,
            ChatMessage msg) {
        if (msg instanceof ToolExecutionResultMessage trm) {
            service.record(baseEntry(process, engineName, turnId, seq)
                    .direction(LlmTraceDirection.TOOL_RESULT)
                    .role("tool")
                    .toolName(trm.toolName())
                    .toolCallId(trm.id())
                    .content(trm.text())
                    .build());
            return seq + 1;
        }
        // Treat AiMessage in the request (e.g. replayed history) as an
        // INPUT — it's part of what the model sees, not a fresh OUTPUT.
        service.record(baseEntry(process, engineName, turnId, seq)
                .direction(LlmTraceDirection.INPUT)
                .role(roleOf(msg))
                .content(textOf(msg))
                .build());
        return seq + 1;
    }

    private static int recordResponse(
            LlmTraceService service,
            ThinkProcessDocument process,
            String engineName,
            String turnId,
            int seq,
            ChatResponse response,
            long elapsedMs) {
        AiMessage ai = response.aiMessage();
        TokenUsage usage = response.tokenUsage();
        Integer tokensIn = usage == null ? null : usage.inputTokenCount();
        Integer tokensOut = usage == null ? null : usage.outputTokenCount();
        Integer cacheCreate = null;
        Integer cacheRead = null;
        if (usage instanceof AnthropicTokenUsage atu) {
            cacheCreate = (int) atu.getCacheCreationInputTokens();
            cacheRead = (int) atu.getCacheReadInputTokens();
        }

        // OUTPUT row — assistant reply text. Always written even for
        // empty replies so the operator can tell "model said nothing"
        // from "model wasn't called".
        service.record(baseEntry(process, engineName, turnId, seq)
                .direction(LlmTraceDirection.OUTPUT)
                .role("assistant")
                .content(ai == null ? null : safeText(ai))
                .tokensIn(tokensIn)
                .tokensOut(tokensOut)
                .cacheCreationInputTokens(cacheCreate)
                .cacheReadInputTokens(cacheRead)
                .elapsedMs(elapsedMs)
                .build());
        seq++;

        // One TOOL_CALL row per request the assistant emitted.
        if (ai != null && ai.hasToolExecutionRequests()) {
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                service.record(baseEntry(process, engineName, turnId, seq)
                        .direction(LlmTraceDirection.TOOL_CALL)
                        .role("assistant")
                        .toolName(req.name())
                        .toolCallId(req.id())
                        .content(req.arguments())
                        .build());
                seq++;
            }
        }
        return seq;
    }

    private static LlmTraceDocument.LlmTraceDocumentBuilder baseEntry(
            ThinkProcessDocument process, String engineName, String turnId, int seq) {
        return LlmTraceDocument.builder()
                .tenantId(process.getTenantId() == null ? "" : process.getTenantId())
                .sessionId(process.getSessionId())
                .processId(process.getId() == null ? "" : process.getId())
                .engine(engineName)
                .turnId(turnId)
                .sequence(seq);
    }

    private static String roleOf(ChatMessage m) {
        if (m instanceof SystemMessage) return "system";
        if (m instanceof UserMessage) return "user";
        if (m instanceof AiMessage) return "assistant";
        if (m instanceof ToolExecutionResultMessage) return "tool";
        return m.getClass().getSimpleName();
    }

    private static String textOf(ChatMessage m) {
        if (m instanceof SystemMessage s) return safeOrEmpty(s.text());
        if (m instanceof UserMessage u) {
            try {
                return safeOrEmpty(u.singleText());
            } catch (RuntimeException e) {
                return "(multimodal user message)";
            }
        }
        if (m instanceof AiMessage a) return safeText(a);
        if (m instanceof ToolExecutionResultMessage t) return safeOrEmpty(t.text());
        return m.toString();
    }

    private static String safeText(AiMessage a) {
        StringBuilder sb = new StringBuilder();
        if (a.text() != null && !a.text().isEmpty()) sb.append(a.text());
        if (a.hasToolExecutionRequests()) {
            for (ToolExecutionRequest req : a.toolExecutionRequests()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[tool-call ").append(req.name()).append("] ")
                        .append(req.arguments());
            }
        }
        return sb.toString();
    }

    private static String safeOrEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
