package de.mhus.vance.brain.progress;

import de.mhus.vance.api.progress.MetricsPayload;
import de.mhus.vance.api.progress.PlanPayload;
import de.mhus.vance.api.progress.ProcessProgressNotification;
import de.mhus.vance.api.progress.ProgressKind;
import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.progress.UsageDelta;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single emit-path for the user-progress side-channel. Engines, the
 * AI-layer hook, and the tool-executor decorator all funnel through
 * here; the source block ({@code processId}, {@code processName},
 * {@code engine}, {@code sessionId}, {@code parentProcessId}, …) is
 * derived from the supplied {@link ThinkProcessDocument} so callers
 * cannot accidentally ship a notification without it.
 *
 * <p>The per-process verbosity is read from
 * {@code engineParams[progress]} (see {@link ProgressLevel}) and applied
 * before the publish — silent drop if the kind/tag combination is
 * suppressed at the configured level.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgressEmitter {

    private final ClientEventPublisher events;

    public void emitMetrics(ThinkProcessDocument process, MetricsPayload payload) {
        if (!shouldEmit(process, ProgressKind.METRICS, null)) {
            return;
        }
        ProcessProgressNotification msg = envelope(process, ProgressKind.METRICS)
                .metrics(payload)
                .build();
        publish(process, msg);
    }

    public void emitPlan(ThinkProcessDocument process, PlanPayload payload) {
        if (!shouldEmit(process, ProgressKind.PLAN, null)) {
            return;
        }
        ProcessProgressNotification msg = envelope(process, ProgressKind.PLAN)
                .plan(payload)
                .build();
        publish(process, msg);
    }

    public void emitStatus(ThinkProcessDocument process, StatusPayload payload) {
        if (!shouldEmit(process, ProgressKind.STATUS, payload.getTag())) {
            return;
        }
        ProcessProgressNotification msg = envelope(process, ProgressKind.STATUS)
                .status(payload)
                .build();
        publish(process, msg);
    }

    /** Convenience overload — most status pings only need a tag and a short text. */
    public void emitStatus(ThinkProcessDocument process, StatusTag tag, String text) {
        emitStatus(process, StatusPayload.builder().tag(tag).text(text).build());
    }

    /**
     * Opens a correlated operation: emits an open-tag ping
     * ({@link StatusTag#TOOL_START}, {@link StatusTag#DELEGATING}, …) carrying
     * a freshly minted {@code operationId}, and returns that id so the caller
     * can pass it to {@link #closeOperation} when the operation finishes.
     *
     * <p>The id is also returned when the underlying {@link ProgressLevel}
     * filter would suppress the push — callers don't have to special-case
     * {@code null}, and a later {@link #closeOperation} with the id will
     * also be suppressed consistently.
     */
    public String openOperation(ThinkProcessDocument process, StatusTag tag, String text) {
        String operationId = UUID.randomUUID().toString();
        emitStatus(process, StatusPayload.builder()
                .tag(tag)
                .text(text)
                .operationId(operationId)
                .build());
        return operationId;
    }

    /**
     * Closes a correlated operation: emits a close-tag ping
     * ({@link StatusTag#TOOL_END}, {@link StatusTag#NODE_DONE},
     * {@link StatusTag#PHASE_DONE}) carrying the same {@code operationId}
     * that {@link #openOperation} returned, plus the operation's
     * {@link UsageDelta}.
     */
    public void closeOperation(
            ThinkProcessDocument process,
            String operationId,
            StatusTag tag,
            String text,
            @Nullable UsageDelta usage) {
        emitStatus(process, StatusPayload.builder()
                .tag(tag)
                .text(text)
                .operationId(operationId)
                .usage(usage)
                .build());
    }

    // ──────────────────────────────────────────────────────────────

    private boolean shouldEmit(ThinkProcessDocument process, ProgressKind kind, StatusTag tag) {
        if (process.getId() == null || process.getSessionId() == null
                || process.getSessionId().isBlank()) {
            return false;
        }
        ProgressLevel level = ProgressLevel.parse(
                process.getEngineParams() == null
                        ? null
                        : process.getEngineParams().get(ProgressLevel.PARAM_KEY));
        return level.allows(kind, tag);
    }

    private ProcessProgressNotification.ProcessProgressNotificationBuilder envelope(
            ThinkProcessDocument process, ProgressKind kind) {
        return ProcessProgressNotification.builder()
                .processId(process.getId())
                .processName(process.getName())
                .processTitle(process.getTitle())
                .engine(process.getThinkEngine())
                .sessionId(process.getSessionId())
                .parentProcessId(process.getParentProcessId())
                .kind(kind)
                .emittedAt(Instant.now());
    }

    private void publish(ThinkProcessDocument process, ProcessProgressNotification msg) {
        events.publish(process.getSessionId(), MessageType.PROCESS_PROGRESS, msg);
    }
}
