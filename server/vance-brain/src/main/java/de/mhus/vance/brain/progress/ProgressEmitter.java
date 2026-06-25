package de.mhus.vance.brain.progress;

import de.mhus.vance.api.progress.MetricsPayload;
import de.mhus.vance.api.progress.PlanPayload;
import de.mhus.vance.api.progress.ProcessProgressNotification;
import de.mhus.vance.api.progress.ProgressKind;
import de.mhus.vance.api.progress.ReplyPayload;
import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.progress.UsageDelta;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
    /**
     * Lazy via {@link ObjectProvider} to break the bean-graph cycle:
     * {@code EngineMessageRouter} depends on {@code ProcessEventEmitter},
     * which depends on {@code ProgressEmitter} for engine-turn-boundary
     * pings. Direct injection would close the loop.
     */
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;

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
     * Engine emits a semantic reply — the worker's complete answer for
     * one turn. Dual-routed:
     *
     * <ol>
     *   <li>{@code PROCESS_PROGRESS} push to the session's clients so
     *       the live UI can render the reply alongside metrics/plan/
     *       status updates.</li>
     *   <li>If {@code process.parentProcessId} is set, a
     *       {@link PendingMessageType#REPLY} row is enqueued on the
     *       parent's pending inbox via the
     *       {@link EngineMessageRouter} (cross-pod aware). The parent's
     *       lane wakes and drains it on its next turn as
     *       {@code SteerMessage.Reply}.</li>
     * </ol>
     *
     * <p>Discipline: callers emit only complete results, not progress
     * fragments. See {@code planning/process-engine-reply-channel.md}.
     *
     * @param process         the emitting worker process
     * @param content         the full reply text — non-blank
     * @param inResponseToAt  timestamp of the user-input turn this
     *                        reply answers, or {@code null} for engine-
     *                        driven replies
     * @param payload         optional structured side-channel data
     */
    public void emitReply(
            ThinkProcessDocument process,
            String content,
            @Nullable Instant inResponseToAt,
            @Nullable Map<String, Object> payload) {
        if (content == null || content.isBlank()) {
            return;
        }
        ReplyPayload replyPayload = ReplyPayload.builder()
                .content(content)
                .inResponseToAt(inResponseToAt)
                .payload(payload)
                .interim(false)
                .build();
        // a) Client-facing PROCESS_PROGRESS — applies the same level filter
        //    as the other progress kinds so a `progress=quiet` recipe still
        //    suppresses the WS push (the parent-routing below is
        //    independent and always fires when a parent exists, because
        //    it is the load-bearing parent-notification path, not UI).
        if (shouldEmit(process, ProgressKind.REPLY, null)) {
            ProcessProgressNotification msg = envelope(process, ProgressKind.REPLY)
                    .reply(replyPayload)
                    .build();
            publish(process, msg);
        }
        // b) Parent-inbox routing — REPLY is the only kind that flows up.
        String parentId = process.getParentProcessId();
        if (parentId == null || parentId.isBlank()) {
            return;
        }
        PendingMessageDocument doc = PendingMessageDocument.builder()
                .type(PendingMessageType.REPLY)
                .at(Instant.now())
                .sourceProcessId(process.getId())
                .fromUser(process.getName())   // worker name surfaces to the parent
                .content(content)
                .payload(payload)
                .inResponseToAt(inResponseToAt)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
        boolean ok = messageRouterProvider.getObject()
                .dispatch(process.getId(), parentId, doc);
        if (!ok) {
            log.warn("Reply dropped — parent process not found / cross-pod push failed parent='{}' child='{}'",
                    parentId, process.getId());
        } else {
            log.debug("Reply queued parent='{}' child='{}' content-length={}",
                    parentId, process.getId(), content.length());
        }
    }

    /**
     * Emits an <em>interim</em> reply — a live working-log entry that
     * lets the user follow a long-running engine loop (Lunkwill narrates
     * between tool batches) in real time. Differs from
     * {@link #emitReply} in two ways:
     *
     * <ol>
     *   <li>The {@link ReplyPayload#isInterim() interim} flag is set, so
     *       clients can render the message visually dimmed and
     *       in-line — it's not the canonical turn answer.</li>
     *   <li>Parent-inbox routing is skipped. Interim replies are pure
     *       UI signal; only the canonical reply at turn-end crosses
     *       the worker→parent boundary.</li>
     * </ol>
     *
     * <p>The {@link ProgressLevel} filter for {@link ProgressKind#REPLY}
     * applies the same way as for canonical replies — a {@code
     * progress=quiet} recipe suppresses both.
     *
     * <p>Blank content is silently dropped (mirrors {@link #emitReply}).
     */
    public void emitInterimReply(
            ThinkProcessDocument process,
            String content,
            @Nullable Instant inResponseToAt) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!shouldEmit(process, ProgressKind.REPLY, null)) {
            return;
        }
        ReplyPayload replyPayload = ReplyPayload.builder()
                .content(content)
                .inResponseToAt(inResponseToAt)
                .interim(true)
                .build();
        ProcessProgressNotification msg = envelope(process, ProgressKind.REPLY)
                .reply(replyPayload)
                .build();
        publish(process, msg);
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
