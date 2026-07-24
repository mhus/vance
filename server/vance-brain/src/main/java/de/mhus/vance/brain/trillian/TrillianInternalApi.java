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
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
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
 * the same session. That's enough for Nature-0; cross-session /
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
     * <p>Generic by design — Nature-0 might render attributes as a
     * persona block, Nature-A might use one attribute as a
     * token-budget hint, Nature-B as a mode pre-selection. The
     * naming convention is the caller's (Control LLM) responsibility;
     * recipes can document expected keys per Nature.
     *
     * <p>Nature-0: ephemeral — gone on session-close. Persistent
     * storage (home-project document) comes with Nature-A+.
     */
    public static final String PARAM_ATTRIBUTES = "attributes";

    private final ThinkProcessService thinkProcessService;
    private final EngineMessageRouter messageRouter;
    private final EngineMessageService engineMessageService;
    private final ProcessEventEmitter eventEmitter;
    private final ChatMessageService chatMessageService;

    /**
     * Resolves the peer-process for {@code callingProcessId} via its
     * stored {@link TrillianSessionBootstrapper#PARAM_PEER_PROCESS_ID}.
     * The peer lives in a <b>different session</b> in v2 — same
     * tenant is the only constraint (Cross-Tenant explicitly out of
     * Nature-0).
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
        return Optional.of(eventId);
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
        List<EngineMessageDocument> queued = engineMessageService.drainInbox(targetProcessId);
        if (queued.isEmpty()) {
            return 0;
        }
        List<String> ids = queued.stream()
                .map(EngineMessageDocument::getMessageId)
                .filter(java.util.Objects::nonNull)
                .toList();
        engineMessageService.markDrained(ids);
        log.info("Trillian cleared {} pending messages for process id='{}'",
                ids.size(), targetProcessId);
        return ids.size();
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
     * as caller and observed live in the same tenant — Nature-0 stays
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
        return thinkProcessService.replaceEngineParams(peer.getId(), params);
    }

    /**
     * Removes all attributes from the peer (Trillian-User) process.
     * Sets the attributes map to empty rather than removing the key —
     * downstream readers can safely assume the key exists.
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
        Map<String, Object> params = new LinkedHashMap<>();
        if (peer.getEngineParams() != null) {
            params.putAll(peer.getEngineParams());
        }
        params.put(PARAM_ATTRIBUTES, new LinkedHashMap<>());
        thinkProcessService.replaceEngineParams(peer.getId(), params);
        return existing.size();
    }
}
