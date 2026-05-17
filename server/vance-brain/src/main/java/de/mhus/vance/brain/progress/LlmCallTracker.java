package de.mhus.vance.brain.progress;

import de.mhus.vance.api.progress.MetricsPayload;
import de.mhus.vance.brain.ai.LlmCallStatsLogger;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Per-process cumulative LLM telemetry. Engines call
 * {@link #record(ThinkProcessDocument, ChatResponse, long, String)} after
 * every LLM round-trip; this class folds the deltas into an in-memory
 * counter and asks {@link ProgressEmitter} to push a metrics snapshot.
 *
 * <p>Counters are not persisted in v1 — they live in a process-scoped map
 * and reset on brain restart. The push-snapshot is what the user-facing
 * HUD renders; durable usage tracking will arrive with the quota system.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmCallTracker {

    private final ProgressEmitter emitter;
    private final MetricService metricService;

    private final ConcurrentMap<String, AtomicReference<Counters>> byProcess = new ConcurrentHashMap<>();

    /**
     * Folds {@code response.tokenUsage()} and {@code elapsedMs} into the
     * cumulative state for {@code process} and emits a metrics snapshot.
     * No-op when the response has no token usage and the call is too cheap
     * to be worth a notification — safe to invoke unconditionally after
     * any chat round-trip.
     */
    public void record(
            ThinkProcessDocument process,
            @Nullable ChatRequest request,
            @Nullable ChatResponse response,
            long elapsedMs,
            @Nullable String modelAlias) {

        if (process.getId() == null) {
            return;
        }

        TokenUsage usage = response == null ? null : response.tokenUsage();
        int dTokensIn = tokens(usage == null ? null : usage.inputTokenCount());
        int dTokensOut = tokens(usage == null ? null : usage.outputTokenCount());
        int dCharsIn = LlmCallStatsLogger.countRequestChars(request);
        int dCharsOut = LlmCallStatsLogger.countResponseChars(response);

        Counters next = byProcess
                .computeIfAbsent(process.getId(), k -> new AtomicReference<>(Counters.ZERO))
                .updateAndGet(prev -> prev.plus(
                        dTokensIn, dTokensOut, dCharsIn, dCharsOut, elapsedMs));

        emitter.emitMetrics(process, MetricsPayload.builder()
                .tokensInTotal(next.tokensIn)
                .tokensOutTotal(next.tokensOut)
                .charsInTotal(next.charsIn)
                .charsOutTotal(next.charsOut)
                .llmCallCount(next.calls)
                .elapsedMs(next.elapsedMs)
                .modelAlias(modelAlias)
                .lastCallTokensIn(dTokensIn == 0 ? null : dTokensIn)
                .lastCallTokensOut(dTokensOut == 0 ? null : dTokensOut)
                .lastCallCharsIn(dCharsIn == 0 ? null : dCharsIn)
                .lastCallCharsOut(dCharsOut == 0 ? null : dCharsOut)
                .build());

        // Prometheus telemetry: per-model-alias call count + token
        // counts + latency. modelAlias is intentionally low-cardinality
        // (it's an alias like "default:fast", not the full
        // provider/version string) so it's safe to use as a tag.
        // Char-length summaries are also published from LoggingChatModel
        // under the model-fullname tag; the engine-driven path here is
        // the one wired to the process-scoped progress side-channel.
        String alias = modelAlias == null || modelAlias.isBlank() ? "unknown" : modelAlias;
        metricService.counter("vance.llm.calls", "model", alias).increment();
        metricService.timer("vance.llm.call.duration", "model", alias)
                .record(Duration.ofMillis(elapsedMs));
        if (dTokensIn > 0) {
            metricService.summary("vance.llm.tokens.input", "model", alias).record(dTokensIn);
        }
        if (dTokensOut > 0) {
            metricService.summary("vance.llm.tokens.output", "model", alias).record(dTokensOut);
        }
    }

    /** Drop counters for a process — call when the process is deleted. */
    public void forget(String processId) {
        byProcess.remove(processId);
    }

    /**
     * Cumulative snapshot for {@code processId}. Returns {@link Snapshot#ZERO}
     * when no LLM call has been recorded yet — callers (tool-decorator,
     * marvin/vogon node entry) can subtract two snapshots to get the delta
     * for the operation in between.
     */
    public Snapshot snapshot(@Nullable String processId) {
        if (processId == null) {
            return Snapshot.ZERO;
        }
        AtomicReference<Counters> ref = byProcess.get(processId);
        if (ref == null) {
            return Snapshot.ZERO;
        }
        Counters c = ref.get();
        return new Snapshot(c.tokensIn, c.tokensOut, c.charsIn, c.charsOut, c.calls);
    }

    private static int tokens(@Nullable Integer raw) {
        return raw == null || raw < 0 ? 0 : raw;
    }

    /**
     * Read-only view of the cumulative counters for one process. Wall-clock
     * is intentionally omitted — that's a {@code MetricsPayload} concern;
     * operation-level wall-clock is measured separately by the tool
     * decorator.
     */
    public record Snapshot(long tokensIn, long tokensOut, long charsIn, long charsOut, int calls) {
        public static final Snapshot ZERO = new Snapshot(0, 0, 0, 0, 0);

        public Snapshot minus(Snapshot other) {
            return new Snapshot(
                    tokensIn - other.tokensIn,
                    tokensOut - other.tokensOut,
                    charsIn - other.charsIn,
                    charsOut - other.charsOut,
                    calls - other.calls);
        }
    }

    private record Counters(
            long tokensIn,
            long tokensOut,
            long charsIn,
            long charsOut,
            int calls,
            long elapsedMs) {
        static final Counters ZERO = new Counters(0, 0, 0, 0, 0, 0);

        Counters plus(int dTokensIn, int dTokensOut, int dCharsIn, int dCharsOut, long dMs) {
            return new Counters(
                    tokensIn + dTokensIn,
                    tokensOut + dTokensOut,
                    charsIn + dCharsIn,
                    charsOut + dCharsOut,
                    calls + 1,
                    elapsedMs + Math.max(0, dMs));
        }
    }
}
