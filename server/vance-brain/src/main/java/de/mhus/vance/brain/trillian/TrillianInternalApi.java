package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.util.MongoKeys;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
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
                ? null : caller.getEngineParams().get(
                        TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID);
        if (!(peerIdRaw instanceof String peerId) || peerId.isBlank()) {
            return Optional.empty();
        }
        Optional<ThinkProcessDocument> peer = thinkProcessService.findById(peerId);
        if (peer.isEmpty()) {
            log.warn("Trillian peer process id='{}' (recorded on caller id='{}') is gone",
                    peerId, callingProcessId);
            return Optional.empty();
        }
        ThinkProcessDocument peerDoc = peer.get();
        if (!caller.getTenantId().equals(peerDoc.getTenantId())) {
            log.warn("Trillian peer mismatch: caller tenant='{}' peer tenant='{}' — refusing",
                    caller.getTenantId(), peerDoc.getTenantId());
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
            log.warn("Trillian dispatch failed: sender='{}' target='{}' event='{}'",
                    senderProcessId, targetProcessId, taskEvent);
            return Optional.empty();
        }
        log.info("Trillian dispatched event='{}' taskId='{}' sender='{}' target='{}' eventId='{}'",
                taskEvent, taskId, senderProcessId, targetProcessId, eventId);
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
    private void notifyNatureOfConclusion(
            String senderProcessId, String taskEvent, String taskId, String summary) {
        TrillianNature.TaskOutcome outcome = switch (taskEvent) {
            case TASK_EVENT_DONE -> TrillianNature.TaskOutcome.DONE;
            case TASK_EVENT_FAILED -> TrillianNature.TaskOutcome.FAILED;
            default -> null;
        };
        if (outcome == null) {
            return;
        }
        try {
            ThinkProcessDocument worker = thinkProcessService.findById(senderProcessId)
                    .orElse(null);
            if (worker == null) {
                return;
            }
            String nature = worker.getEngineParams() == null ? null
                    : java.util.Objects.toString(
                            worker.getEngineParams()
                                    .get(TrillianSessionBootstrapper.PARAM_NATURE), null);
            natureRegistry.resolve(nature).taskConcluded(worker, taskId, outcome, summary);
        } catch (RuntimeException e) {
            log.warn("Trillian: nature hook for concluded task '{}' failed: {}",
                    taskId, e.toString());
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
        List<EngineMessageDocument> queued =
                engineMessageService.findInboxedByTargets(List.of(targetProcessId));
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
        log.info("Trillian cleared {} pending message(s) for process id='{}' (onlyTaskRequests={})",
                ids.size(), targetProcessId, onlyTaskRequests);
        return new ClearResult(ids.size(), requests, other);
    }

    /** Outcome of {@link #clearPending(String, boolean)}. */
    public record ClearResult(int total, int taskRequests, int other) {
    }

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
        for (EngineMessageDocument m :
                engineMessageService.findInboxedByTargets(List.of(targetProcessId))) {
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
            @Nullable Instant queuedAt) {
    }

    /**
     * Queues a task for the peer. Shared by {@code task_enqueue} and the
     * {@code //trillian task} command so a task raised by hand is
     * indistinguishable from one Control raised.
     *
     * @return the generated task id, or empty when dispatch failed
     */
    public Optional<String> enqueueTask(
            String senderProcessId, ThinkProcessDocument peer, String description) {
        String taskId = UUID.randomUUID().toString();
        String humanSummary = "Task request: "
                + (description.length() <= 240 ? description : description.substring(0, 237) + "...");
        Optional<String> eventId = dispatchTaskEvent(
                senderProcessId, peer.getId(), TASK_EVENT_REQUEST, taskId, humanSummary,
                Map.of(PAYLOAD_KEY_DESCRIPTION, description));
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
        return new PeerStateSnapshot(
                peer.getId(),
                peer.getName(),
                peer.getStatus(),
                pending);
    }

    /**
     * Pauses the peer worker loop. Runs the status change on the
     * <b>peer's</b> lane, not the caller's — the mutated process is the
     * peer, so that is where serialization belongs, and it keeps the
     * pause working while the caller's own lane is busy.
     *
     * <p>Idempotent: an already PAUSED or CLOSED peer is returned
     * unchanged.
     *
     * @return the peer's status after the call
     */
    public ThinkProcessStatus pausePeer(ThinkProcessDocument peer) {
        return setPeerStatus(peer, ThinkProcessStatus.PAUSED,
                java.util.Set.of(ThinkProcessStatus.PAUSED, ThinkProcessStatus.CLOSED));
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
        // Leaves a non-PAUSED peer's status alone (setPeerStatus is a no-op for
        // CLOSED, and IDLE → IDLE is a write we don't need), then wakes it.
        ThinkProcessStatus now = current == ThinkProcessStatus.IDLE
                ? current
                : setPeerStatus(peer, ThinkProcessStatus.IDLE,
                        java.util.Set.of(ThinkProcessStatus.CLOSED));
        wakePeer(peer.getId());
        return now;
    }

    private ThinkProcessStatus setPeerStatus(
            ThinkProcessDocument peer, ThinkProcessStatus target,
            java.util.Set<ThinkProcessStatus> noOpWhen) {
        ThinkProcessStatus current = peer.getStatus();
        if (noOpWhen.contains(current)) {
            return current;
        }
        try {
            laneScheduler.submit(peer.getId(), () -> {
                thinkProcessService.updateStatus(peer.getId(), target);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted setting peer '" + peer.getId() + "' to " + target, ie);
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new IllegalStateException(
                    "Failed to set peer '" + peer.getId() + "' to " + target
                            + ": " + cause.getMessage(), cause);
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

    public record PeerStateSnapshot(
            String processId,
            String name,
            ThinkProcessStatus status,
            long pendingInboxCount) {
    }

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
    public List<ChatMessageDocument> readChatMemoryOf(
            String callerProcessId,
            String observedProcessId,
            int limit) {
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
            log.warn("Trillian readChatMemory denied: caller tenant='{}' observed tenant='{}'",
                    caller.getTenantId(), observed.getTenantId());
            return List.of();
        }
        List<ChatMessageDocument> full = chatMessageService.activeHistory(
                observed.getTenantId(), observed.getSessionId(), observed.getId());
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
     * Atomically read-modify-writes the {@code engineParams} map.
     *
     * @return {@code true} when the peer existed and was updated
     */
    public boolean setPeerAttribute(String callerProcessId, String name, Object value) {
        Optional<ThinkProcessDocument> peerOpt = findPeer(callerProcessId);
        if (peerOpt.isEmpty()) {
            return false;
        }
        ThinkProcessDocument peer = peerOpt.get();
        Map<String, Object> params = new LinkedHashMap<>();
        if (peer.getEngineParams() != null) {
            params.putAll(peer.getEngineParams());
        }
        Map<String, Object> attributes = new LinkedHashMap<>(readAttributes(peer));
        // The attribute name is LLM-chosen and lands as a nested Mongo map key;
        // a dot would be read as a path separator (project mongo_map_keys
        // gotcha), so escape it to '_'.
        attributes.put(MongoKeys.sanitizeKey(name), value);
        params.put(PARAM_ATTRIBUTES, attributes);
        boolean written = thinkProcessService.replaceEngineParams(peer.getId(), params);
        if (written) {
            notifyNature(peer, attributes);
        }
        return written;
    }

    /**
     * Removes all attributes from the peer (Trillian-User) process.
     * Sets the attributes map to empty rather than removing the key —
     * downstream readers can safely assume the key exists.
     *
     * @return number of attributes cleared, or -1 when peer is missing
     */
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
        Map<String, Object> attributes = readAttributes(peer);
        if (attributes.remove(MongoKeys.sanitizeKey(name)) == null) {
            return false;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (peer.getEngineParams() != null) {
            params.putAll(peer.getEngineParams());
        }
        params.put(PARAM_ATTRIBUTES, attributes);
        boolean written = thinkProcessService.replaceEngineParams(peer.getId(), params);
        if (written) {
            notifyNature(peer, attributes);
        }
        return written;
    }

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
        Map<String, Object> params = new LinkedHashMap<>();
        if (peer.getEngineParams() != null) {
            params.putAll(peer.getEngineParams());
        }
        params.put(PARAM_ATTRIBUTES, new LinkedHashMap<>());
        thinkProcessService.replaceEngineParams(peer.getId(), params);
        notifyNature(peer, Map.of());
        return existing.size();
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
            String nature = worker.getEngineParams() == null ? null
                    : java.util.Objects.toString(
                            worker.getEngineParams()
                                    .get(TrillianSessionBootstrapper.PARAM_NATURE), null);
            natureRegistry.resolve(nature).attributesChanged(worker, attributes);
        } catch (RuntimeException e) {
            log.warn("Trillian: nature hook for attribute change on '{}' failed: {}",
                    worker.getId(), e.toString());
        }
    }
}
