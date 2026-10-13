package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.util.MongoKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Internal API that the Trillian-Control + Trillian-User tools use to
 * talk across the peer-pair boundary. Centralises peer-process lookup,
 * cross-inbox dispatch, and inbox-clear so the individual tool beans
 * stay thin.
 *
 * <p>Not exposed via REST/WS — calls only originate from Java code
 * (the Trillian tools). Authorization is per-call: the caller process
 * must (a) carry a {@link TrillianSessionBootstrapper#PARAM_PEER_PROCESS_ID}
 * in its {@code engineParams} and (b) the resolved peer must live in
 * the same session. That's enough for Nature void; cross-session /
 * cross-tenant reach is not supported.
 *
 * <p>See {@code planning/trillian-engine.md} §5.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianInternalApi {

    /**
     * Marker for Trillian-specific {@code ProcessEvent} payloads. Lets
     * the user-loop's prompt instruct the LLM to recognise these events
     * without burning a top-level {@link ProcessEventType} value.
     */
    public static final String PAYLOAD_KEY_TASK_EVENT = "trillianTaskEvent";

    public static final String PAYLOAD_KEY_TASK_ID = "taskId";
    public static final String PAYLOAD_KEY_DESCRIPTION = "description";
    public static final String PAYLOAD_KEY_RESULT = "result";
    public static final String PAYLOAD_KEY_REASON = "reason";

    /**
     * Task-request payload key: the process the conclusion events
     * ({@code task_done}/{@code task_failed}/{@code task_needs_input}) are
     * routed to instead of the loop's own peer — the conversation head that
     * handed the task over. Absent on Control-raised tasks: their results
     * go to the peer anyway, so an address would only be bookkeeping.
     */
    public static final String PAYLOAD_KEY_REPLY_TO = "replyTo";

    /**
     * engineParams key on the Trillian-User loop: {@code taskId → replyTo
     * process}, recorded when the loop drains a {@code task_request}
     * carrying a return address, consumed when it reports the task, and
     * pruned when the head it points to closes. Every write is one atomic
     * entry ({@code ThinkProcessService.setEngineParamEntry}) — Control
     * writes the same process's {@code attributes} from its own lane, and a
     * whole-map write from either side would roll back the other's.
     */
    public static final String PARAM_TASK_REPLIES = "taskReplies";

    /**
     * Engine role of the processes that may hand a task to their own loop
     * ({@code task_enqueue}): the human-facing Control and the
     * conversation heads. Its own role, not {@code trillian-control}: a
     * head carrying the control role would have every {@code user_*} tool
     * released by role and kept out only by tool membership — one
     * {@code allowedToolsAdd} away from steering the loop like the human.
     */
    public static final String ROLE_TASK_REQUESTER = "trillian-task-requester";

    public static final String TASK_EVENT_REQUEST = "task_request";
    public static final String TASK_EVENT_DONE = "task_done";
    public static final String TASK_EVENT_FAILED = "task_failed";
    public static final String TASK_EVENT_NEEDS_INPUT = "task_needs_input";

    /**
     * {@code engineParams} key under which the Trillian-User
     * process holds its free-form attribute map (typed as
     * {@code Map<String, Object>}). Set/cleared by Control via the
     * {@code user_attr_*} tools; consumed by the active
     * {@code TrillianNature} when composing the system prompt or
     * making behavioural decisions.
     *
     * <p>Generic by design — Nature void might render attributes as a
     * persona block, Nature-A might use one attribute as a
     * token-budget hint, Nature-B as a mode pre-selection. The
     * naming convention is the caller's (Control LLM) responsibility;
     * recipes can document expected keys per Nature.
     *
     * <p>Nature void: ephemeral — gone on session-close. Persistent
     * storage (home-project document) comes with Nature-A+.
     */
    public static final String PARAM_ATTRIBUTES = "attributes";

    private final ThinkProcessService thinkProcessService;
    private final EngineMessageRouter messageRouter;
    private final EngineMessageService engineMessageService;
    private final ProcessEventEmitter eventEmitter;
    private final ChatMessageService chatMessageService;
    private final de.mhus.vance.brain.scheduling.LaneScheduler laneScheduler;
    private final de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry natureRegistry;

    /**
     * Resolves the peer-process for {@code callingProcessId} via its
     * stored {@link TrillianSessionBootstrapper#PARAM_PEER_PROCESS_ID}.
     * The peer lives in a <b>different session</b> in v2 — same
     * tenant is the only constraint (Cross-Tenant explicitly out of
     * Nature void).
     *
     * <p>Returns empty when the caller has no peer recorded, the
     * peer-id points at a missing document, or the tenants don't
     * match.
     */
    public Optional<ThinkProcessDocument> findPeer(String callingProcessId) {
        Optional<ThinkProcessDocument> callerOpt = thinkProcessService.findById(callingProcessId);
        if (callerOpt.isEmpty()) {
            return Optional.empty();
        }
        ThinkProcessDocument caller = callerOpt.get();
        Object peerIdRaw = caller.getEngineParams() == null
                ? null
                : caller.getEngineParams().get(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID);
        if (!(peerIdRaw instanceof String peerId) || peerId.isBlank()) {
            return Optional.empty();
        }
        Optional<ThinkProcessDocument> peer = thinkProcessService.findById(peerId);
        if (peer.isEmpty()) {
            log.warn("Trillian peer process id='{}' (recorded on caller id='{}') is gone", peerId, callingProcessId);
            return Optional.empty();
        }
        ThinkProcessDocument peerDoc = peer.get();
        if (!caller.getTenantId().equals(peerDoc.getTenantId())) {
            log.warn(
                    "Trillian peer mismatch: caller tenant='{}' peer tenant='{}' — refusing",
                    caller.getTenantId(),
                    peerDoc.getTenantId());
            return Optional.empty();
        }
        return Optional.of(peerDoc);
    }

    /**
     * Dispatches a Trillian task ProcessEvent ({@code task_request},
     * {@code task_done}, {@code task_failed}, {@code task_needs_input})
     * into the peer's inbox. Carried as a
     * {@link ProcessEventType#SUMMARY} so the existing routing /
     * drain machinery handles it without ProcessEventType extension.
     *
     * @return generated event-id, or empty when dispatch failed
     */
    public Optional<String> dispatchTaskEvent(
            String senderProcessId,
            String targetProcessId,
            String taskEvent,
            String taskId,
            String humanSummary,
            @Nullable Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_KEY_TASK_EVENT, taskEvent);
        payload.put(PAYLOAD_KEY_TASK_ID, taskId);
        if (extraPayload != null) {
            for (Map.Entry<String, Object> e : extraPayload.entrySet()) {
                payload.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        String eventId = UUID.randomUUID().toString();
        PendingMessageDocument message = PendingMessageDocument.builder()
                .type(PendingMessageType.PROCESS_EVENT)
                .at(Instant.now())
                .sourceProcessId(senderProcessId)
                .eventType(ProcessEventType.SUMMARY)
                .content(humanSummary)
                .payload(payload)
                .eventId(eventId)
                .build();
        boolean ok = messageRouter.dispatch(senderProcessId, targetProcessId, message);
        if (!ok) {
            log.warn(
                    "Trillian dispatch failed: sender='{}' target='{}' event='{}'",
                    senderProcessId,
                    targetProcessId,
                    taskEvent);
            return Optional.empty();
        }
        log.info(
                "Trillian dispatched event='{}' taskId='{}' sender='{}' target='{}' eventId='{}'",
                taskEvent,
                taskId,
                senderProcessId,
                targetProcessId,
                eventId);
        notifyNatureOfConclusion(senderProcessId, taskEvent, taskId, humanSummary);
        return Optional.of(eventId);
    }

    /**
     * Lets the active Nature react to a concluded task.
     *
     * <p>Deliberately after the dispatch: Control hearing the outcome is
     * the point of the call, and must not wait on — or be lost to —
     * whatever a Nature does with it. Only {@code task_done} and
     * {@code task_failed} count as conclusions; a request or a question
     * has nothing to conclude about yet.
     *
     * <p>The call itself is a plain method call on this thread, which
     * still holds the reporting process's lane — ordering after the
     * dispatch is not the same as getting off the thread. A Nature whose
     * reaction is expensive detaches on its own side
     * ({@code TrillianNatureAdam#taskConcluded} is {@code @Async}); this
     * funnel only guarantees that a failure here cannot undo the report.
     */
    private void notifyNatureOfConclusion(String senderProcessId, String taskEvent, String taskId, String summary) {
        TrillianNature.TaskOutcome outcome =
                switch (taskEvent) {
                    case TASK_EVENT_DONE -> TrillianNature.TaskOutcome.DONE;
                    case TASK_EVENT_FAILED -> TrillianNature.TaskOutcome.FAILED;
                    default -> null;
                };
        if (outcome == null) {
            return;
        }
        try {
            ThinkProcessDocument worker =
                    thinkProcessService.findById(senderProcessId).orElse(null);
            if (worker == null) {
                return;
            }
            String nature = worker.getEngineParams() == null
                    ? null
                    : java.util.Objects.toString(
                            worker.getEngineParams().get(TrillianSessionBootstrapper.PARAM_NATURE), null);
            natureRegistry.resolve(nature).taskConcluded(worker, taskId, outcome, summary);
        } catch (RuntimeException e) {
            log.warn("Trillian: nature hook for concluded task '{}' failed: {}", taskId, e.toString());
        }
    }

    /**
     * Drops every undrained message addressed to {@code targetProcessId}
     * by marking it drained — equivalent to "drop the inbox" without
     * the lane consuming. Returns the number of messages cleared.
     *
     * <p>Used by {@code user_clear} (and {@code user_reset}) so the
     * human can wipe queued tasks the Trillian user hasn't picked up
     * yet.
     */
    public int clearPending(String targetProcessId) {
        return clearPending(targetProcessId, /*onlyTaskRequests*/ false).total();
    }

    /**
     * Drops queued messages from the peer's inbox.
     *
     * <p>The inbox is not a task list — it also carries the worker's
     * result events (task_done / worker replies). Dropping those loses
     * work that was already finished: the loop never learns the outcome
     * and the task stays "open" forever. Hence {@code onlyTaskRequests},
     * which removes what is still waiting to be started and leaves
     * results alone.
     *
     * @return counts, so a caller can say what it actually threw away
     */
    public ClearResult clearPending(String targetProcessId, boolean onlyTaskRequests) {
        List<EngineMessageDocument> queued = engineMessageService.findInboxedByTargets(List.of(targetProcessId));
        if (queued.isEmpty()) {
            return new ClearResult(0, 0, 0);
        }
        List<String> ids = new ArrayList<>();
        int requests = 0;
        int other = 0;
        for (EngineMessageDocument m : queued) {
            boolean isRequest = TASK_EVENT_REQUEST.equals(taskEventOf(m));
            if (onlyTaskRequests && !isRequest) {
                continue;
            }
            if (m.getMessageId() != null) {
                ids.add(m.getMessageId());
                if (isRequest) {
                    requests++;
                } else {
                    other++;
                }
            }
        }
        if (ids.isEmpty()) {
            return new ClearResult(0, 0, 0);
        }
        engineMessageService.markDrained(ids);
        log.info(
                "Trillian cleared {} pending message(s) for process id='{}' (onlyTaskRequests={})",
                ids.size(),
                targetProcessId,
                onlyTaskRequests);
        return new ClearResult(ids.size(), requests, other);
    }

    /** Outcome of {@link #clearPending(String, boolean)}. */
    public record ClearResult(int total, int taskRequests, int other) {}

    /**
     * The peer's inbox as it stands, without consuming it — what is
     * waiting to be picked up on the next turn.
     *
     * <p>{@code info} can only report a depth; a number says nothing
     * about whether those are unstarted tasks or results waiting to be
     * reported back.
     */
    public List<PendingEntry> listPending(String targetProcessId) {
        List<PendingEntry> entries = new ArrayList<>();
        for (EngineMessageDocument m : engineMessageService.findInboxedByTargets(List.of(targetProcessId))) {
            entries.add(new PendingEntry(
                    m.getMessageId(),
                    taskEventOf(m),
                    stringPayload(m, PAYLOAD_KEY_TASK_ID),
                    stringPayload(m, PAYLOAD_KEY_DESCRIPTION),
                    m.getCreatedAt()));
        }
        return entries;
    }

    /**
     * One queued message. {@code taskEvent} is null for anything that is
     * not a Trillian task event — user input, plain replies.
     */
    public record PendingEntry(
            String messageId,
            @Nullable String taskEvent,
            @Nullable String taskId,
            @Nullable String description,
            @Nullable Instant queuedAt) {}

    /**
     * Queues a task for the peer. Shared by {@code task_enqueue} and the
     * {@code //trillian task} command so a task raised by hand is
     * indistinguishable from one Control raised.
     *
     * @return the generated task id, or empty when dispatch failed
     */
    public Optional<String> enqueueTask(String senderProcessId, ThinkProcessDocument peer, String description) {
        return enqueueTask(senderProcessId, peer, description, /*replyToProcessId*/ null);
    }

    /**
     * Queues a task for the peer, with an explicit return address.
     *
     * <p>{@code replyToProcessId} is where the conclusion events
     * ({@code task_done}/{@code task_failed}/{@code task_needs_input}) are
     * routed when the loop reports this task — the conversation head that
     * handed the work over, so the loop's answer lands in the thread the
     * task came from. {@code null} keeps the historic behaviour: conclusions
     * go to the loop's peer, the human's Control. Control itself passes its
     * own id or null — both are the same address, so one rule covers both:
     * <b>results return to the requester.</b> An address equal to the loop's
     * peer is dropped here rather than carried: the report falls back to the
     * peer anyway, and an entry nobody needs would only grow the loop's map.
     *
     * <p>The loop records the mapping when it drains the request — this
     * payload only carries it.
     *
     * @return the generated task id, or empty when dispatch failed
     */
    public Optional<String> enqueueTask(
            String senderProcessId, ThinkProcessDocument peer, String description, @Nullable String replyToProcessId) {
        String taskId = UUID.randomUUID().toString();
        String humanSummary =
                "Task request: " + (description.length() <= 240 ? description : description.substring(0, 237) + "...");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_KEY_DESCRIPTION, description);
        if (replyToProcessId != null
                && !replyToProcessId.isBlank()
                && !replyToProcessId.equals(paramString(peer, TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID))) {
            payload.put(PAYLOAD_KEY_REPLY_TO, replyToProcessId);
        }
        Optional<String> eventId =
                dispatchTaskEvent(senderProcessId, peer.getId(), TASK_EVENT_REQUEST, taskId, humanSummary, payload);
        return eventId.isEmpty() ? Optional.empty() : Optional.of(taskId);
    }

    private static @Nullable String taskEventOf(EngineMessageDocument m) {
        return stringPayload(m, PAYLOAD_KEY_TASK_EVENT);
    }

    private static @Nullable String stringPayload(EngineMessageDocument m, String key) {
        Map<String, Object> payload = m.getPayload();
        if (payload == null) {
            return null;
        }
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * Snapshot of the peer's runtime state: status + inbox depth. Used
     * by {@code user_status} to surface what the worker is doing.
     */
    public PeerStateSnapshot snapshotPeerState(ThinkProcessDocument peer) {
        long pending = engineMessageService.countInbox(peer.getId());
        return new PeerStateSnapshot(peer.getId(), peer.getName(), peer.getStatus(), pending);
    }

    /**
     * How long a caller waits for a peer-lane status write before giving
     * up on the confirmation. The write itself stays queued — the wait is
     * only about how long a request thread stays bound to it. Generous
     * enough that a healthy lane always confirms, short enough that a
     * wedged one cannot hold a WebSocket thread for a whole model call.
     */
    private static final long LANE_WAIT_SECONDS = 10;

    /**
     * Pauses the peer worker loop.
     *
     * <p><b>Does not wait for the peer's lane.</b> The whole point of
     * {@code //trillian stop} is availability while a turn is stuck
     * ({@code specification/trillian-engine.md} §6a), and
     * {@code TrillianCommandHandler.runsOnLane()} is {@code false} — the
     * command runs inline on the WebSocket receive thread. Waiting for a
     * lane task there means waiting for exactly the turn that is being
     * stopped, on exactly the thread that has to answer the client.
     *
     * <p>So the halt flag goes out first, out-of-band and immediately:
     * that is the channel a mid-turn engine reads (see
     * {@code OrchestratorInterrupt}), and it is what makes the stop take
     * effect within the current turn instead of after it. The queued lane
     * task then writes {@code PAUSED} and clears the flag at the next safe
     * boundary — same shape as
     * {@code SessionLifecycleService.pauseActiveInSession}, minus the
     * join.
     *
     * <p>Idempotent: an already PAUSED or CLOSED peer is returned
     * unchanged.
     *
     * @return the status the peer is being taken to — {@code PAUSED}
     *         unless the call was a no-op
     */
    public ThinkProcessStatus pausePeer(ThinkProcessDocument peer) {
        ThinkProcessStatus current = peer.getStatus();
        if (current == ThinkProcessStatus.PAUSED || current == ThinkProcessStatus.CLOSED) {
            return current;
        }
        thinkProcessService.requestHalt(peer.getId());
        laneScheduler.submit(peer.getId(), () -> {
            thinkProcessService.updateStatus(peer.getId(), ThinkProcessStatus.PAUSED);
            thinkProcessService.clearHalt(peer.getId());
            return null;
        });
        return ThinkProcessStatus.PAUSED;
    }

    /**
     * Resumes a paused peer and schedules a turn so queued work is picked up
     * without waiting for the next event. Only a PAUSED peer changes status;
     * anything else keeps the one it has.
     *
     * <p>The wake-up is <b>not</b> conditional on a status change. An IDLE peer
     * with a non-empty inbox is precisely the case a human reaches for
     * {@code //trillian continue} / {@code user_continue}: nothing to un-pause,
     * but a lane-turn is exactly what is missing. Only CLOSED (gone) and RUNNING
     * (already turning) are left completely alone.
     *
     * @return the peer's status after the call
     */
    public ThinkProcessStatus resumePeer(ThinkProcessDocument peer) {
        ThinkProcessStatus current = peer.getStatus();
        if (current == ThinkProcessStatus.CLOSED || current == ThinkProcessStatus.RUNNING) {
            return current;
        }
        // A pause may have left the out-of-band halt flag set (the lane task
        // that clears it runs after the paused turn, and a pause that never
        // reached a turn leaves it standing). Un-pausing without clearing it
        // hands the peer a turn that bails at its first loop-head check.
        thinkProcessService.clearHalt(peer.getId());
        // Leaves a non-PAUSED peer's status alone (setPeerStatus is a no-op for
        // CLOSED, and IDLE → IDLE is a write we don't need), then wakes it.
        ThinkProcessStatus now = current == ThinkProcessStatus.IDLE
                ? current
                : setPeerStatus(peer, ThinkProcessStatus.IDLE, java.util.Set.of(ThinkProcessStatus.CLOSED));
        wakePeer(peer.getId());
        return now;
    }

    /**
     * Records the return addresses of drained task requests on the loop —
     * one atomic entry per task, so Control's concurrent attribute writes on
     * the same process can neither lose an address nor be rolled back by
     * one.
     *
     * @param replies {@code taskId → replyTo process id}
     * @return how many entries could not be written (the process vanished)
     */
    public int recordReplyTo(String loopProcessId, Map<String, String> replies) {
        int failed = 0;
        for (Map.Entry<String, String> e : replies.entrySet()) {
            try {
                if (!thinkProcessService.setEngineParamEntry(
                        loopProcessId, PARAM_TASK_REPLIES, e.getKey(), e.getValue())) {
                    failed++;
                }
            } catch (IllegalArgumentException notAKey) {
                // Task ids are generated UUIDs; one that is not a valid map
                // key did not come from enqueueTask and has no address.
                failed++;
            }
        }
        return failed;
    }

    /**
     * The return address a task was enqueued with, if any, without
     * removing it — for {@code task_needs_input}: the task is not over, and
     * its final report must still find its way back to the head that asked.
     *
     * @return the reply-to process id, or empty when the task has none
     */
    public Optional<String> peekReplyTo(String loopProcessId, String taskId) {
        return thinkProcessService.findById(loopProcessId).flatMap(loop -> replyToOf(loop, taskId));
    }

    /**
     * The return address a task was enqueued with, if any — consumed by
     * the reporting tool: the loop's answer to a task raised by a
     * conversation head goes to that head (which posts it into its thread),
     * everything else goes to the peer as before. Removing the entry here
     * keeps the map bounded by the open tasks; what a report never consumes
     * (a task abandoned after {@code task_needs_input}) is pruned when its
     * head closes ({@link #pruneRepliesTo}).
     *
     * @return the reply-to process id, or empty when the task has none (or
     *         is unknown — a loop rebuilt with an old inbox lost it; the
     *         report then falls back to the peer, the historic target)
     */
    public Optional<String> consumeReplyTo(String loopProcessId, String taskId) {
        Optional<String> target = peekReplyTo(loopProcessId, taskId);
        if (target.isPresent()) {
            thinkProcessService.setEngineParamEntry(loopProcessId, PARAM_TASK_REPLIES, taskId, null);
        }
        return target;
    }

    /**
     * Removes every return address pointing at {@code replyToProcessId} — the
     * head closed, and a report for one of its tasks has nobody to post it
     * any more (it falls back to the peer, where a human can still see it).
     *
     * @return the number of entries removed
     */
    public int pruneRepliesTo(String loopProcessId, String replyToProcessId) {
        Optional<ThinkProcessDocument> loop = thinkProcessService.findById(loopProcessId);
        if (loop.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<String, Object> e : taskRepliesOf(loop.get()).entrySet()) {
            if (replyToProcessId.equals(e.getValue())) {
                thinkProcessService.setEngineParamEntry(loopProcessId, PARAM_TASK_REPLIES, e.getKey(), null);
                removed++;
            }
        }
        return removed;
    }

    private static Optional<String> replyToOf(ThinkProcessDocument loop, String taskId) {
        Object target = taskRepliesOf(loop).get(taskId);
        return target instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    private static Map<String, Object> taskRepliesOf(ThinkProcessDocument loop) {
        Object raw =
                loop.getEngineParams() == null ? null : loop.getEngineParams().get(PARAM_TASK_REPLIES);
        Map<String, Object> replies = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String k) {
                    replies.put(k, e.getValue());
                }
            }
        }
        return replies;
    }

    private static @Nullable String paramString(ThinkProcessDocument process, String key) {
        Object raw = process.getEngineParams() == null
                ? null
                : process.getEngineParams().get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * Writes a status on the peer's lane and waits — bounded — for it to
     * land. The mutated process is the peer, so that is where
     * serialization belongs.
     *
     * <p>The wait is capped at {@link #LANE_WAIT_SECONDS}: callers reach
     * this from a WebSocket receive thread, and an unbounded {@code get()}
     * on a busy lane binds that thread for the length of a model call.
     * A timeout is not a failure — the task stays queued and the write
     * still happens — so the target status is returned either way.
     */
    private ThinkProcessStatus setPeerStatus(
            ThinkProcessDocument peer, ThinkProcessStatus target, java.util.Set<ThinkProcessStatus> noOpWhen) {
        ThinkProcessStatus current = peer.getStatus();
        if (noOpWhen.contains(current)) {
            return current;
        }
        try {
            laneScheduler
                    .submit(peer.getId(), () -> {
                        thinkProcessService.updateStatus(peer.getId(), target);
                        return null;
                    })
                    .get(LANE_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted setting peer '" + peer.getId() + "' to " + target, ie);
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn(
                    "Trillian: peer '{}' lane did not confirm {} within {}s — " + "the write stays queued",
                    peer.getId(),
                    target,
                    LANE_WAIT_SECONDS);
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new IllegalStateException(
                    "Failed to set peer '" + peer.getId() + "' to " + target + ": " + cause.getMessage(), cause);
        }
        return target;
    }

    /**
     * Schedules a lane-turn on the peer so the engine notices freshly
     * appended events promptly. Idempotent — engines that are PAUSED /
     * SUSPENDED / CLOSED quietly skip.
     */
    public void wakePeer(String targetProcessId) {
        eventEmitter.scheduleTurn(targetProcessId);
    }

    public record PeerStateSnapshot(String processId, String name, ThinkProcessStatus status, long pendingInboxCount) {}

    /**
     * Live children in the peer's session, i.e. the per-task workers the
     * loop spawned. The loop itself is filtered out — it is reported as the
     * worker — as are closed processes. Shared by {@code //trillian info}
     * and the insights state view, so both see the same definition of
     * "task worker".
     */
    public List<TaskWorkerSnapshot> listTaskWorkers(ThinkProcessDocument peer) {
        List<TaskWorkerSnapshot> workers = new ArrayList<>();
        for (ThinkProcessDocument p : thinkProcessService.findBySession(peer.getTenantId(), peer.getSessionId())) {
            if (p.getId().equals(peer.getId()) || p.getStatus() == ThinkProcessStatus.CLOSED) {
                continue;
            }
            // Conversation heads share the session but are not task workers:
            // they are the Trillian talking, not the Trillian working; their
            // state lives in their thread and the head sweep, not the work
            // views.
            if (TrillianHeadService.headThreadOf(p) != null) {
                continue;
            }
            workers.add(new TaskWorkerSnapshot(
                    p.getName(), p.getId(), p.getProjectId(), p.getStatus(), p.getThinkEngine(), p.getCreatedAt()));
        }
        return workers;
    }

    /** One live per-task worker of a Trillian user-loop. */
    public record TaskWorkerSnapshot(
            String name,
            String processId,
            String projectId,
            ThinkProcessStatus status,
            String engine,
            @Nullable Instant createdAt) {}

    /**
     * Reads the (active, non-archived) chat history of a process the
     * caller can observe. Cross-session reads are permitted as long
     * as caller and observed live in the same tenant — Nature void stays
     * within one tenant, Cross-Tenant is out of scope.
     *
     * <p>The returned list is the chronological transcript — newest
     * messages last. {@code limit} caps the number of messages
     * returned (most recent {@code limit}).
     *
     * @return empty when the caller or observed process is missing,
     *         or when they don't share a tenant
     */
    public List<ChatMessageDocument> readChatMemoryOf(String callerProcessId, String observedProcessId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Optional<ThinkProcessDocument> callerOpt = thinkProcessService.findById(callerProcessId);
        Optional<ThinkProcessDocument> observedOpt = thinkProcessService.findById(observedProcessId);
        if (callerOpt.isEmpty() || observedOpt.isEmpty()) {
            return List.of();
        }
        ThinkProcessDocument caller = callerOpt.get();
        ThinkProcessDocument observed = observedOpt.get();
        if (!caller.getTenantId().equals(observed.getTenantId())) {
            log.warn(
                    "Trillian readChatMemory denied: caller tenant='{}' observed tenant='{}'",
                    caller.getTenantId(),
                    observed.getTenantId());
            return List.of();
        }
        List<ChatMessageDocument> full =
                chatMessageService.activeHistory(observed.getTenantId(), observed.getSessionId(), observed.getId());
        if (full.size() <= limit) {
            return full;
        }
        return full.subList(full.size() - limit, full.size());
    }

    // ──────────────────── Trillian-User attributes ────────────────────

    /**
     * Reads the attributes map ({@link #PARAM_ATTRIBUTES}) from the
     * given process's {@code engineParams}. Returns an empty map
     * when none is set. The returned map is a defensive copy — safe
     * to inspect but not back-write.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readAttributes(ThinkProcessDocument process) {
        if (process.getEngineParams() == null) {
            return new LinkedHashMap<>();
        }
        Object raw = process.getEngineParams().get(PARAM_ATTRIBUTES);
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k) {
                    copy.put(k, e.getValue());
                }
            }
            return copy;
        }
        return new LinkedHashMap<>();
    }

    /**
     * Sets a single attribute on the peer (Trillian-User) process.
     *
     * <p>One atomic entry write ({@code engineParams.attributes.<key>}):
     * the loop writes other keys of the same {@code engineParams} from its
     * own lane (task return addresses), and a whole-map write-back from here
     * would roll those back — the race this used to tolerate while Control
     * was the only writer.
     *
     * @return {@code true} when the peer existed and was updated
     */
    public boolean setPeerAttribute(String callerProcessId, String name, Object value) {
        Optional<ThinkProcessDocument> peerOpt = findPeer(callerProcessId);
        if (peerOpt.isEmpty()) {
            return false;
        }
        // The attribute name is LLM-chosen and lands as a nested Mongo map key;
        // a dot would be read as a path separator (project mongo_map_keys
        // gotcha), so escape it to '_'.
        boolean written = thinkProcessService.setEngineParamEntry(
                peerOpt.get().getId(), PARAM_ATTRIBUTES, MongoKeys.sanitizeKey(name), value);
        if (written) {
            notifyNatureFresh(peerOpt.get());
        }
        return written;
    }

    /**
     * Removes one attribute from the peer. Returns {@code false} when
     * there is no peer or the key was not set — a delete of something
     * absent is worth saying, not worth failing.
     */
    public boolean removePeerAttribute(String callerProcessId, String name) {
        Optional<ThinkProcessDocument> peerOpt = findPeer(callerProcessId);
        if (peerOpt.isEmpty()) {
            return false;
        }
        ThinkProcessDocument peer = peerOpt.get();
        String key = MongoKeys.sanitizeKey(name);
        if (!readAttributes(peer).containsKey(key)) {
            return false;
        }
        boolean written = thinkProcessService.setEngineParamEntry(peer.getId(), PARAM_ATTRIBUTES, key, null);
        if (written) {
            notifyNatureFresh(peer);
        }
        return written;
    }

    /**
     * Removes all attributes from the peer (Trillian-User) process — entry
     * by entry, so the map itself stays (downstream readers can assume the
     * key exists) and nothing else in {@code engineParams} is touched.
     *
     * @return number of attributes cleared, or -1 when peer is missing
     */
    public int clearPeerAttributes(String callerProcessId) {
        Optional<ThinkProcessDocument> peerOpt = findPeer(callerProcessId);
        if (peerOpt.isEmpty()) {
            return -1;
        }
        ThinkProcessDocument peer = peerOpt.get();
        Map<String, Object> existing = readAttributes(peer);
        if (existing.isEmpty()) {
            return 0;
        }
        for (String key : existing.keySet()) {
            thinkProcessService.setEngineParamEntry(peer.getId(), PARAM_ATTRIBUTES, key, null);
        }
        notifyNatureFresh(peer);
        return existing.size();
    }

    /**
     * Notifies the Nature with the attribute map as it stands after the
     * write — re-read, because an entry write does not hand the whole map
     * back, and the Nature mirrors the whole map.
     */
    private void notifyNatureFresh(ThinkProcessDocument peer) {
        ThinkProcessDocument fresh = thinkProcessService.findById(peer.getId()).orElse(peer);
        notifyNature(fresh, readAttributes(fresh));
    }

    /**
     * Tells the active Nature that the map changed, so a persistent one
     * can mirror it. The single place all three mutations pass through —
     * {@code user_attr_*} and {@code //trillian attr} share this API, and
     * a Nature that only heard about one of them would be worse than one
     * that hears about neither.
     *
     * <p>Swallows: durability is not worth failing the write that already
     * succeeded.
     */
    private void notifyNature(ThinkProcessDocument worker, Map<String, Object> attributes) {
        try {
            String nature = worker.getEngineParams() == null
                    ? null
                    : java.util.Objects.toString(
                            worker.getEngineParams().get(TrillianSessionBootstrapper.PARAM_NATURE), null);
            natureRegistry.resolve(nature).attributesChanged(worker, attributes);
        } catch (RuntimeException e) {
            log.warn("Trillian: nature hook for attribute change on '{}' failed: {}", worker.getId(), e.toString());
        }
    }
}
