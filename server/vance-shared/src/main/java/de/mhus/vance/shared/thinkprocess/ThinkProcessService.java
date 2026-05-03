package de.mhus.vance.shared.thinkprocess;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Lifecycle and lookup for {@link ThinkProcessDocument}. The one entry
 * point to think-process data.
 *
 * <p>Status transitions run through {@link #updateStatus} so callers don't
 * accidentally overwrite other fields on a read-modify-write cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThinkProcessService {

    private final ThinkProcessRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final EngineMessageService engineMessageService;

    // ────────────────── Create ──────────────────

    /**
     * Creates a new think-process inside {@code sessionId}. Throws
     * {@link ThinkProcessAlreadyExistsException} if one with the same
     * {@code name} already exists in that session.
     */
    public ThinkProcessDocument create(
            String tenantId,
            @Nullable String projectId,
            String sessionId,
            String name,
            String thinkEngine,
            @Nullable String thinkEngineVersion,
            @Nullable String title,
            @Nullable String goal) {
        return create(tenantId, projectId, sessionId, name, thinkEngine,
                thinkEngineVersion, title, goal,
                /*parentProcessId*/ null, /*engineParams*/ null);
    }

    /**
     * Variant that records the orchestrator parent — see
     * {@link ThinkProcessDocument#getParentProcessId()}.
     */
    public ThinkProcessDocument create(
            String tenantId,
            @Nullable String projectId,
            String sessionId,
            String name,
            String thinkEngine,
            @Nullable String thinkEngineVersion,
            @Nullable String title,
            @Nullable String goal,
            @Nullable String parentProcessId) {
        return create(tenantId, projectId, sessionId, name, thinkEngine,
                thinkEngineVersion, title, goal, parentProcessId,
                /*engineParams*/ null);
    }

    /**
     * Mid create — also stores engine-specific runtime parameters
     * (model override, validation flag, etc.). The map's schema is
     * defined per-engine; pass {@code null} to record an empty map.
     */
    public ThinkProcessDocument create(
            String tenantId,
            @Nullable String projectId,
            String sessionId,
            String name,
            String thinkEngine,
            @Nullable String thinkEngineVersion,
            @Nullable String title,
            @Nullable String goal,
            @Nullable String parentProcessId,
            @Nullable Map<String, Object> engineParams) {
        return create(tenantId, projectId, sessionId, name, thinkEngine,
                thinkEngineVersion, title, goal, parentProcessId,
                engineParams,
                /*recipeName*/ null,
                /*promptOverride*/ null,
                /*promptMode*/ null,
                /*allowedToolsOverride*/ null);
    }

    /**
     * Mid create — accepts recipe-derived fields. Used by the
     * recipe-aware spawn paths after {@code RecipeResolver.apply}.
     */
    public ThinkProcessDocument create(
            String tenantId,
            @Nullable String projectId,
            String sessionId,
            String name,
            String thinkEngine,
            @Nullable String thinkEngineVersion,
            @Nullable String title,
            @Nullable String goal,
            @Nullable String parentProcessId,
            @Nullable Map<String, Object> engineParams,
            @Nullable String recipeName,
            @Nullable String promptOverride,
            @Nullable PromptMode promptMode,
            @Nullable Set<String> allowedToolsOverride) {
        return create(tenantId, projectId, sessionId, name, thinkEngine,
                thinkEngineVersion, title, goal, parentProcessId,
                engineParams, recipeName,
                promptOverride, /*promptOverrideSmall*/ null, promptMode,
                /*dataRelayCorrectionOverride*/ null,
                allowedToolsOverride,
                /*connectionProfile*/ null,
                /*defaultActiveSkills*/ null,
                /*allowedSkillsOverride*/ null);
    }

    /**
     * Full create — also accepts the size-aware prompt variant, the
     * per-recipe validator overrides, and the connection-profile that
     * was active at spawn time (audit-only).
     */
    public ThinkProcessDocument create(
            String tenantId,
            @Nullable String projectId,
            String sessionId,
            String name,
            String thinkEngine,
            @Nullable String thinkEngineVersion,
            @Nullable String title,
            @Nullable String goal,
            @Nullable String parentProcessId,
            @Nullable Map<String, Object> engineParams,
            @Nullable String recipeName,
            @Nullable String promptOverride,
            @Nullable String promptOverrideSmall,
            @Nullable PromptMode promptMode,
            @Nullable String dataRelayCorrectionOverride,
            @Nullable Set<String> allowedToolsOverride,
            @Nullable String connectionProfile,
            @Nullable List<String> defaultActiveSkills,
            @Nullable Set<String> allowedSkillsOverride) {
        if (repository.existsByTenantIdAndSessionIdAndName(tenantId, sessionId, name)) {
            throw new ThinkProcessAlreadyExistsException(
                    "Think-process '" + name + "' already exists in session '"
                            + sessionId + "' (tenant='" + tenantId + "')");
        }
        Map<String, Object> params = engineParams == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(engineParams);
        Set<String> allowed = allowedToolsOverride == null
                ? null : new LinkedHashSet<>(allowedToolsOverride);
        Set<String> skillWhitelist = allowedSkillsOverride == null
                ? null : new LinkedHashSet<>(allowedSkillsOverride);
        List<ActiveSkillRefEmbedded> seededSkills = seedActiveSkills(defaultActiveSkills);
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId == null ? "" : projectId)
                .sessionId(sessionId)
                .name(name)
                .title(title)
                .thinkEngine(thinkEngine)
                .thinkEngineVersion(thinkEngineVersion)
                .goal(goal)
                .parentProcessId(parentProcessId)
                .engineParams(params)
                .recipeName(recipeName)
                .connectionProfile(connectionProfile)
                .promptOverride(promptOverride)
                .promptOverrideSmall(promptOverrideSmall)
                .promptMode(promptMode == null ? PromptMode.APPEND : promptMode)
                .dataRelayCorrectionOverride(dataRelayCorrectionOverride)
                .allowedToolsOverride(allowed)
                .allowedSkillsOverride(skillWhitelist)
                .activeSkills(seededSkills)
                .status(ThinkProcessStatus.INIT)
                .build();
        ThinkProcessDocument saved = repository.save(doc);
        log.info("Created think-process tenant='{}' session='{}' name='{}' engine='{}' id='{}' parent='{}' recipe='{}' profile='{}' skills={} params={}",
                tenantId, sessionId, name, thinkEngine, saved.getId(), parentProcessId,
                recipeName, connectionProfile,
                seededSkills.isEmpty() ? "[]" : seededSkills.stream().map(ActiveSkillRefEmbedded::getName).toList(),
                params.isEmpty() ? "{}" : params.keySet());
        return saved;
    }

    /**
     * Builds the initial {@code activeSkills} list from the recipe's
     * {@code defaultActiveSkills} names. Each entry is sticky and
     * marked {@code fromRecipe=true} so {@code /skill clear} respects
     * the recipe author's intent (see
     * {@link de.mhus.vance.shared.skill.ActiveSkillRefEmbedded}).
     * {@code resolvedFromScope} is left at the safe default
     * {@link SkillScope#RESOURCE}; engines re-resolve the actual scope
     * on every turn anyway.
     */
    private static List<ActiveSkillRefEmbedded> seedActiveSkills(
            @Nullable List<String> defaultActiveSkills) {
        if (defaultActiveSkills == null || defaultActiveSkills.isEmpty()) {
            return new ArrayList<>();
        }
        Instant now = Instant.now();
        List<ActiveSkillRefEmbedded> out = new ArrayList<>(defaultActiveSkills.size());
        for (String name : defaultActiveSkills) {
            if (name == null || name.isBlank()) continue;
            out.add(ActiveSkillRefEmbedded.builder()
                    .name(name)
                    .resolvedFromScope(SkillScope.RESOURCE)
                    .oneShot(false)
                    .fromRecipe(true)
                    .activatedAt(now)
                    .build());
        }
        return out;
    }

    // ────────────────── Read ──────────────────

    public Optional<ThinkProcessDocument> findById(String id) {
        return repository.findById(id);
    }

    public Optional<ThinkProcessDocument> findByName(
            String tenantId, String sessionId, String name) {
        return repository.findByTenantIdAndSessionIdAndName(tenantId, sessionId, name);
    }

    public List<ThinkProcessDocument> findBySession(String tenantId, String sessionId) {
        return repository.findByTenantIdAndSessionId(tenantId, sessionId);
    }

    public List<ThinkProcessDocument> findBySessionAndStatus(
            String tenantId, String sessionId, ThinkProcessStatus status) {
        return repository.findByTenantIdAndSessionIdAndStatus(tenantId, sessionId, status);
    }

    // ────────────────── Mutations ──────────────────

    /**
     * Atomically replaces the {@code engineParams} map. Used by
     * stateful engines (Vogon's strategyState, future Marvin run-state)
     * that need to persist their internal mutable state on every
     * advance. The whole map is replaced — callers should read,
     * modify, and pass the full map.
     *
     * @return {@code true} if the row exists and was updated
     */
    public boolean replaceEngineParams(String id, Map<String, Object> engineParams) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("engineParams",
                engineParams == null ? new LinkedHashMap<>() : engineParams);
        UpdateResult result = mongoTemplate.updateFirst(
                query, update, ThinkProcessDocument.class);
        return result.getModifiedCount() > 0;
    }

    /**
     * Atomically replaces the {@code activeSkills} list. Used by the
     * skill-steer pipeline (activate/clear) and by the engine itself
     * after draining one-shot skills at end-of-turn.
     *
     * @return {@code true} if the row exists and was updated
     */
    public boolean replaceActiveSkills(String id, List<ActiveSkillRefEmbedded> activeSkills) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("activeSkills",
                activeSkills == null ? new ArrayList<>() : activeSkills);
        UpdateResult result = mongoTemplate.updateFirst(
                query, update, ThinkProcessDocument.class);
        return result.getModifiedCount() > 0;
    }

    /**
     * Atomically sets / clears {@code activeDelegationWorkerId} on the
     * given process. Pass {@code null} to clear. Used by chat-
     * orchestrator engines (Arthur) to remember which child worker is
     * currently the auto-forward target for raw user-chat-input —
     * so the LLM never has to re-derive worker names from history.
     */
    public boolean updateActiveDelegationWorkerId(String id, @Nullable String workerId) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("activeDelegationWorkerId", workerId);
        UpdateResult result = mongoTemplate.updateFirst(
                query, update, ThinkProcessDocument.class);
        return result.getModifiedCount() > 0;
    }

    /**
     * Atomically sets {@code status} on the process with the given Mongo id.
     * For non-CLOSED transitions {@code closeReason} is cleared. For
     * CLOSED transitions, {@code closeReason} <em>must</em> be set —
     * the convenience method {@link #closeProcess} does this for you.
     *
     * <p>Publishes a {@link ThinkProcessStatusChangedEvent} after a
     * successful update so listeners (e.g. parent-notification) can
     * react. The event is published <em>even when the new status
     * equals the prior</em> — listeners that care about transitions
     * filter on {@code priorStatus != newStatus} themselves; those
     * that just want a heartbeat can ignore the predicate.
     *
     * @return {@code true} if the row exists and was updated
     */
    public boolean updateStatus(String id, ThinkProcessStatus status) {
        if (status == ThinkProcessStatus.CLOSED) {
            throw new IllegalArgumentException(
                    "updateStatus(CLOSED) requires a CloseReason — use closeProcess(id, reason)");
        }
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update()
                .set("status", status)
                .unset("closeReason");
        ThinkProcessDocument prior = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(false),
                ThinkProcessDocument.class);
        if (prior == null) {
            return false;
        }
        log.debug("Think-process status updated id='{}' {} -> {}",
                id, prior.getStatus(), status);
        eventPublisher.publishEvent(new ThinkProcessStatusChangedEvent(
                id,
                prior.getTenantId(),
                prior.getSessionId(),
                prior.getParentProcessId(),
                prior.getStatus(),
                status));
        return true;
    }

    /**
     * Terminal transition: status → CLOSED with the given
     * {@link CloseReason}. Publishes a
     * {@link ThinkProcessStatusChangedEvent} so the parent-notification
     * listener can react. Idempotent — re-closing an already-closed
     * process returns {@code false} without firing the event.
     *
     * @return {@code true} if the row existed and was transitioned
     */
    public boolean closeProcess(String id, CloseReason reason) {
        Query query = new Query(Criteria.where("_id").is(id)
                .and("status").ne(ThinkProcessStatus.CLOSED));
        Update update = new Update()
                .set("status", ThinkProcessStatus.CLOSED)
                .set("closeReason", reason);
        ThinkProcessDocument prior = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(false),
                ThinkProcessDocument.class);
        if (prior == null) {
            return false;
        }
        log.info("Think-process closed id='{}' {} -> CLOSED reason={}",
                id, prior.getStatus(), reason);
        eventPublisher.publishEvent(new ThinkProcessStatusChangedEvent(
                id,
                prior.getTenantId(),
                prior.getSessionId(),
                prior.getParentProcessId(),
                prior.getStatus(),
                ThinkProcessStatus.CLOSED));
        return true;
    }

    // ────────────────── Pending Queue ──────────────────
    // Façade over EngineMessageService — the per-process inbox now lives in
    // the engine_messages collection (see specification/engine-message-routing.md).
    // The legacy embedded ThinkProcessDocument.pendingMessages list is no
    // longer written or read by these methods; it remains on the document
    // for one cleanup phase and will be removed in the final migration step.

    /**
     * Persists one message into the target process's inbox without
     * recording a sender identity. Equivalent to
     * {@link #appendPending(String, PendingMessageDocument, String)} with
     * {@code senderProcessId = ""} — for legacy call-sites that don't yet
     * thread through the originating process id.
     */
    public boolean appendPending(String processId, PendingMessageDocument message) {
        return appendPending(processId, message, "");
    }

    /**
     * Persists one message into the target process's inbox, recording
     * {@code senderProcessId} on the resulting {@link EngineMessageDocument}.
     * Returns {@code true} if the target process exists.
     *
     * <p>Write-through: ack-on-persist semantics — the message is durable
     * (and idempotently dedup'd by {@code messageId}) the moment this returns.
     *
     * <p>The sender id is the {@link ThinkProcessDocument#getId()} of the
     * process that produced the message — Eddie when she steers a worker,
     * Arthur when he steers a sibling, the system component when there is
     * no owning process. Pass empty string when no sender is meaningful.
     * The receiver-side dedup logic ignores the sender; it's audit and
     * routing metadata for future cross-pod replay.
     */
    public boolean appendPending(String processId, PendingMessageDocument message,
                                 @Nullable String senderProcessId) {
        Optional<ThinkProcessDocument> target = repository.findById(processId);
        if (target.isEmpty()) {
            log.warn("Pending append failed — process not found id='{}'", processId);
            return false;
        }
        EngineMessageDocument incoming = toEngineMessage(
                message, processId, target.get().getTenantId(),
                senderProcessId == null ? "" : senderProcessId);
        engineMessageService.acceptDelivery(incoming);
        log.debug("Pending append id='{}' type={} messageId='{}' sender='{}'",
                processId, message.getType(), incoming.getMessageId(),
                incoming.getSenderProcessId());
        return true;
    }

    /**
     * Reads and consumes the pending inbox of {@code processId}.
     *
     * <p>Returns the messages that were delivered but not yet drained,
     * in insertion order. Returns an empty list if the process has no
     * pending work — never {@code null}. New messages that arrive
     * after this call become available for the next lane-turn
     * (Auto-Wakeup).
     */
    public List<PendingMessageDocument> drainPending(String processId) {
        List<EngineMessageDocument> docs = engineMessageService.drainInbox(processId);
        if (docs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = docs.stream().map(EngineMessageDocument::getMessageId).toList();
        engineMessageService.markDrained(ids);
        log.debug("Pending drain id='{}' count={}", processId, docs.size());
        return docs.stream().map(this::toPendingMessage).toList();
    }

    // ─────────── Legacy <-> EngineMessage conversion ───────────
    // These helpers bridge the {@link PendingMessageDocument} façade
    // (which the engine and tool layers still pass around) to the
    // {@link EngineMessageDocument} persistence form. They will be
    // removed when the engine layer adopts EngineMessage natively.

    private EngineMessageDocument toEngineMessage(
            PendingMessageDocument m, String targetProcessId, String tenantId,
            String senderProcessId) {
        String messageId = (m.getIdempotencyKey() != null && !m.getIdempotencyKey().isBlank())
                ? m.getIdempotencyKey()
                : UUID.randomUUID().toString();
        Instant createdAt = m.getAt() == null || m.getAt().equals(Instant.EPOCH)
                ? Instant.now() : m.getAt();
        return EngineMessageDocument.builder()
                .messageId(messageId)
                .tenantId(tenantId == null ? "" : tenantId)
                .senderProcessId(senderProcessId == null ? "" : senderProcessId)
                .targetProcessId(targetProcessId)
                .createdAt(createdAt)
                .type(m.getType())
                .fromUser(m.getFromUser())
                .content(m.getContent())
                .sourceProcessId(m.getSourceProcessId())
                .eventType(m.getEventType())
                .toolCallId(m.getToolCallId())
                .toolName(m.getToolName())
                .toolStatus(m.getToolStatus())
                .error(m.getError())
                .command(m.getCommand())
                .inboxItemId(m.getInboxItemId())
                .inboxItemType(m.getInboxItemType())
                .inboxAnswer(m.getInboxAnswer())
                .sourceEddieProcessId(m.getSourceEddieProcessId())
                .peerUserId(m.getPeerUserId())
                .peerEventType(m.getPeerEventType())
                .payload(m.getPayload())
                .build();
    }

    private PendingMessageDocument toPendingMessage(EngineMessageDocument e) {
        return PendingMessageDocument.builder()
                .at(e.getCreatedAt())
                .idempotencyKey(e.getMessageId())
                .type(e.getType())
                .fromUser(e.getFromUser())
                .content(e.getContent())
                .sourceProcessId(e.getSourceProcessId())
                .eventType(e.getEventType())
                .toolCallId(e.getToolCallId())
                .toolName(e.getToolName())
                .toolStatus(e.getToolStatus())
                .error(e.getError())
                .command(e.getCommand())
                .inboxItemId(e.getInboxItemId())
                .inboxItemType(e.getInboxItemType())
                .inboxAnswer(e.getInboxAnswer())
                .sourceEddieProcessId(e.getSourceEddieProcessId())
                .peerUserId(e.getPeerUserId())
                .peerEventType(e.getPeerEventType())
                .payload(e.getPayload())
                .build();
    }

    /**
     * Sets the out-of-band {@code haltRequested} flag. Used by the
     * pause/stop dispatch to ask cooperatively-yielding engines
     * (notably Arthur's drain-loop) to bail out of their current
     * runTurn so the queued status-transition task on the lane can
     * actually fire. Atomic — does not race with status updates.
     *
     * <p>Returns {@code true} if the row exists.
     */
    public boolean requestHalt(String id) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("haltRequested", true);
        return mongoTemplate.updateFirst(query, update, ThinkProcessDocument.class)
                .getModifiedCount() > 0;
    }

    /**
     * Clears the {@code haltRequested} flag — called when the
     * pause-task on the lane has actually fired (status now
     * {@code PAUSED}, the flag served its purpose) and on resume.
     */
    public void clearHalt(String id) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("haltRequested", false);
        mongoTemplate.updateFirst(query, update, ThinkProcessDocument.class);
    }

    /**
     * Cheap, race-free probe for the {@code haltRequested} flag —
     * engines call this between drain-loop iterations.
     */
    public boolean isHaltRequested(String id) {
        return repository.findById(id)
                .map(ThinkProcessDocument::isHaltRequested)
                .orElse(false);
    }

    /**
     * Cheap inspection of pending-queue length without consuming it.
     * Returns {@code 0} if the process is unknown.
     */
    public int pendingSize(String processId) {
        return (int) engineMessageService.countInbox(processId);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    /** Drops all processes of a session (session-close cleanup). */
    public long deleteBySession(String tenantId, String sessionId) {
        long n = repository.deleteByTenantIdAndSessionId(tenantId, sessionId);
        if (n > 0) {
            log.info("Deleted {} think-processes for session tenant='{}' session='{}'",
                    n, tenantId, sessionId);
        }
        return n;
    }

    public static class ThinkProcessAlreadyExistsException extends RuntimeException {
        public ThinkProcessAlreadyExistsException(String message) {
            super(message);
        }
    }
}
