package de.mhus.vance.shared.thinkprocess;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
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
                /*intentCorrectionOverride*/ null,
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
            @Nullable String intentCorrectionOverride,
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
                .intentCorrectionOverride(intentCorrectionOverride)
                .dataRelayCorrectionOverride(dataRelayCorrectionOverride)
                .allowedToolsOverride(allowed)
                .allowedSkillsOverride(skillWhitelist)
                .activeSkills(seededSkills)
                .status(ThinkProcessStatus.READY)
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
     * Atomically sets {@code status} on the process with the given Mongo id.
     * Returns {@code true} if the row exists, {@code false} if the id is
     * unknown.
     *
     * <p>Publishes a {@link ThinkProcessStatusChangedEvent} after a
     * successful update so listeners (e.g. parent-notification) can
     * react. The event is published <em>even when the new status
     * equals the prior</em> — listeners that care about transitions
     * filter on {@code priorStatus != newStatus} themselves; those
     * that just want a heartbeat can ignore the predicate.
     */
    public boolean updateStatus(String id, ThinkProcessStatus status) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("status", status);
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

    // ────────────────── Pending Queue ──────────────────

    /**
     * Atomically appends one message to the process's pending inbox.
     * Returns {@code true} if the row exists and was updated.
     *
     * <p>Write-through: the append is independent of any in-flight
     * lane-turn — the message is durable the moment this returns.
     */
    public boolean appendPending(String processId, PendingMessageDocument message) {
        Query query = new Query(Criteria.where("_id").is(processId));
        Update update = new Update().push("pendingMessages", message);
        UpdateResult result = mongoTemplate.updateFirst(
                query, update, ThinkProcessDocument.class);
        boolean ok = result.getModifiedCount() > 0;
        if (ok) {
            log.debug("Pending append id='{}' type={} ", processId, message.getType());
        } else {
            log.warn("Pending append failed — process not found id='{}'", processId);
        }
        return ok;
    }

    /**
     * Atomically reads and clears the pending inbox of {@code processId}.
     *
     * <p>Returns the messages that were queued, in insertion order.
     * Returns an empty list if the process is unknown or had no
     * pending work — never {@code null}. New messages that arrive
     * after this call land in the freshly-emptied queue and feed the
     * next lane-turn (Auto-Wakeup).
     */
    public List<PendingMessageDocument> drainPending(String processId) {
        Query query = new Query(Criteria.where("_id").is(processId));
        Update update = new Update().set("pendingMessages", new ArrayList<>());
        ThinkProcessDocument prior = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(false),
                ThinkProcessDocument.class);
        if (prior == null) {
            return Collections.emptyList();
        }
        List<PendingMessageDocument> drained = prior.getPendingMessages();
        if (drained == null || drained.isEmpty()) {
            return Collections.emptyList();
        }
        log.debug("Pending drain id='{}' count={}", processId, drained.size());
        return drained;
    }

    /**
     * Cheap inspection of pending-queue length without consuming it.
     * Returns {@code 0} if the process is unknown.
     */
    public int pendingSize(String processId) {
        return repository.findById(processId)
                .map(d -> d.getPendingMessages() == null ? 0 : d.getPendingMessages().size())
                .orElse(0);
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
