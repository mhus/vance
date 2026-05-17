package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.metric.MetricService;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compact, greppable per-call stats record for every LLM round-trip.
 * Complements {@link AiTraceLogger}: where the trace logger dumps the
 * full prompt and reply across many lines, this one writes a single
 * key=value line with model, character lengths, token counts and
 * latency — easy to {@code grep '\[llm-stats\]'} out of an operator
 * log to answer "how big was that turn, how long did it take".
 *
 * <p>Logger name: {@code de.mhus.vance.brain.ai.stats}. Configure
 * to TRACE to enable. Zero-cost when off — character counting is
 * skipped along with the format.
 *
 * <p>Also pushes two Micrometer distribution summaries
 * ({@code vance.llm.chars.input}, {@code vance.llm.chars.output}) when
 * a {@link MetricService} is supplied. Token counts and call durations
 * stay in {@link de.mhus.vance.brain.progress.LlmCallTracker} to avoid
 * double-counting — this class adds the dimension that was missing
 * (character lengths) and provides the single-line trace.
 */
public final class LlmCallStatsLogger {

    private static final Logger LOG = LoggerFactory.getLogger(
            "de.mhus.vance.brain.ai.stats");

    private LlmCallStatsLogger() {}

    public static boolean traceEnabled() {
        return LOG.isTraceEnabled();
    }

    /**
     * Record one round-trip. Safe to call with {@code response == null}
     * (e.g. on streaming error) — char/token counts then come out as
     * zero, finish reason empty.
     *
     * @param model      chat name; conventionally {@code provider:modelName}
     *                   from {@link AiChatConfig#fullName()}
     * @param request    the outbound request
     * @param response   the model's reply, or {@code null} on failure
     * @param elapsedMs  wall-clock time of the call, milliseconds
     * @param metricService non-null to also push char-length metrics
     */
    public static void record(
            String model,
            @Nullable ChatRequest request,
            @Nullable ChatResponse response,
            long elapsedMs,
            @Nullable MetricService metricService) {
        boolean trace = traceEnabled();
        if (!trace && metricService == null) {
            return;
        }
        int charsIn = countRequestChars(request);
        int charsOut = countResponseChars(response);
        TokenUsage usage = response == null ? null : response.tokenUsage();
        int tokensIn = unboxInt(usage == null ? null : usage.inputTokenCount());
        int tokensOut = unboxInt(usage == null ? null : usage.outputTokenCount());
        long cacheRead = 0;
        long cacheCreate = 0;
        if (usage instanceof CacheAwareTokenUsage cau) {
            cacheRead = cau.cacheReadInputTokens();
            cacheCreate = cau.cacheCreationInputTokens();
        }
        if (trace) {
            String finish = response == null || response.finishReason() == null
                    ? ""
                    : response.finishReason().toString();
            LOG.trace(
                    "[llm-stats] model={} chars_in={} chars_out={} "
                            + "tokens_in={} tokens_out={} "
                            + "cache_read={} cache_create={} "
                            + "duration_ms={} finish={}",
                    model,
                    charsIn,
                    charsOut,
                    tokensIn,
                    tokensOut,
                    cacheRead,
                    cacheCreate,
                    elapsedMs,
                    finish);
        }
        if (metricService != null) {
            String tag = model == null || model.isBlank() ? "unknown" : model;
            metricService.summary("vance.llm.chars.input", "model", tag).record(charsIn);
            metricService.summary("vance.llm.chars.output", "model", tag).record(charsOut);
        }
    }

    // ──────────────────── char counting ────────────────────

    static int countRequestChars(@Nullable ChatRequest request) {
        if (request == null) return 0;
        List<ChatMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) return 0;
        long total = 0;
        for (ChatMessage m : messages) {
            total += charsOf(m);
        }
        return clampInt(total);
    }

    static int countResponseChars(@Nullable ChatResponse response) {
        if (response == null) return 0;
        AiMessage ai = response.aiMessage();
        if (ai == null) return 0;
        long total = 0;
        if (ai.text() != null) {
            total += ai.text().length();
        }
        if (ai.hasToolExecutionRequests()) {
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                if (req.name() != null) total += req.name().length();
                if (req.arguments() != null) total += req.arguments().length();
            }
        }
        return clampInt(total);
    }

    private static long charsOf(ChatMessage m) {
        if (m instanceof SystemMessage s) {
            return s.text() == null ? 0 : s.text().length();
        }
        if (m instanceof UserMessage u) {
            try {
                String t = u.singleText();
                return t == null ? 0 : t.length();
            } catch (RuntimeException e) {
                // Multimodal user message — only the text portion counts.
                // Image/PDF bytes are not characters and would dwarf the
                // signal we care about. Walk contents and sum text parts.
                long sum = 0;
                if (u.contents() != null) {
                    for (Object c : u.contents()) {
                        if (c instanceof dev.langchain4j.data.message.TextContent tc
                                && tc.text() != null) {
                            sum += tc.text().length();
                        }
                    }
                }
                return sum;
            }
        }
        if (m instanceof AiMessage a) {
            long sum = 0;
            if (a.text() != null) sum += a.text().length();
            if (a.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : a.toolExecutionRequests()) {
                    if (req.name() != null) sum += req.name().length();
                    if (req.arguments() != null) sum += req.arguments().length();
                }
            }
            return sum;
        }
        if (m instanceof ToolExecutionResultMessage t) {
            return t.text() == null ? 0 : t.text().length();
        }
        return 0;
    }

    private static int unboxInt(@Nullable Integer v) {
        return v == null ? 0 : v;
    }

    private static int clampInt(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < 0) return 0;
        return (int) v;
    }
}
