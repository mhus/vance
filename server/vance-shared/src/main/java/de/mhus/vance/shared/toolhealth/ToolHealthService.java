package de.mhus.vance.shared.toolhealth;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.api.toolhealth.ToolHealthStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Read + write surface for tool-health state. The two callers are
 * {@code AgrajagChecker} (sync path in vance-brain) and the lifecycle
 * code (system events like Foot-disconnect, MCP-close). LLM-facing
 * write tools live separately and route through this service.
 *
 * <p>Lookup follows the scope cascade SESSION → USER → PROJECT →
 * TENANT → GLOBAL (narrowest first). See
 * {@code specification/tool-availability.md} §4.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolHealthService {

    public static final int HISTORY_MAX_ENTRIES = 20;

    private static final String F_TENANT = "tenantId";
    private static final String F_SCOPE = "scope";
    private static final String F_SCOPE_ID = "scopeId";
    private static final String F_TOOL = "toolName";
    private final MongoTemplate mongoTemplate;
    private final ToolHealthRepository repository;

    // ───────────────────────────────── Read paths

    /**
     * Looks up the narrowest available health entry for the given tool
     * across the scope cascade. Returns empty when nothing matches —
     * callers should treat that as {@code status=OK}.
     */
    public Optional<ToolHealthDocument> lookup(
            String tenantId,
            @Nullable String sessionId,
            @Nullable String userId,
            @Nullable String projectId,
            String toolName) {

        // Build a single Mongo query with OR over the (scope, scopeId)
        // tuples and sort by scope priority in-app. Five sequential
        // findById calls would be the simpler implementation but the
        // single round-trip is cheap and matters when the manifest
        // builder iterates dozens of tools.
        List<Criteria> orClauses = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            orClauses.add(scopeMatch(ToolHealthScope.SESSION, sessionId));
        }
        if (userId != null && !userId.isBlank()) {
            orClauses.add(scopeMatch(ToolHealthScope.USER, userId));
        }
        if (projectId != null && !projectId.isBlank()) {
            orClauses.add(scopeMatch(ToolHealthScope.PROJECT, projectId));
        }
        orClauses.add(scopeMatch(ToolHealthScope.TENANT, tenantId));
        orClauses.add(scopeMatch(ToolHealthScope.GLOBAL, ""));

        Criteria base = Criteria.where(F_TENANT).is(tenantId)
                .and(F_TOOL).is(toolName);
        Criteria or = new Criteria().orOperator(orClauses.toArray(new Criteria[0]));
        Query q = new Query(new Criteria().andOperator(base, or));
        List<ToolHealthDocument> hits = mongoTemplate.find(q, ToolHealthDocument.class);
        if (hits.isEmpty()) return Optional.empty();
        hits.sort((a, b) -> scopeRank(a.getScope()) - scopeRank(b.getScope()));
        return Optional.of(hits.get(0));
    }

    /**
     * Lists every health record (independent of status) inside a
     * {@code (scope, scopeId)} tuple. Used by the Insights UI to show
     * a per-project / per-session health summary with cooldowns.
     * Returns an empty list when nothing is recorded.
     */
    public List<ToolHealthDocument> listForScope(
            String tenantId, ToolHealthScope scope, String scopeId) {
        return repository.findByTenantIdAndScopeAndScopeId(
                tenantId, scope, nullToEmpty(scopeId));
    }

    /**
     * Returns the active cooldown for {@code errorSignature} (and optional
     * {@code userId}) on the narrowest matching health entry, if any —
     * "active" meaning {@code nextSpawnAllowedAt > now}. Returns empty
     * when no cooldown is set or it has already expired.
     */
    public Optional<ToolHealthCooldown> lookupActiveCooldown(
            String tenantId,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            String errorSignature,
            @Nullable String userId,
            Instant now) {
        Optional<ToolHealthDocument> doc = repository
                .findByTenantIdAndScopeAndScopeIdAndToolName(
                        tenantId, scope, nullToEmpty(scopeId), toolName);
        if (doc.isEmpty()) return Optional.empty();
        for (ToolHealthCooldown c : doc.get().getCooldowns()) {
            if (matches(c, errorSignature, userId)
                    && c.getNextSpawnAllowedAt() != null
                    && c.getNextSpawnAllowedAt().isAfter(now)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    // ───────────────────────────────── Status writes

    /**
     * Sets {@code status=DOWN} on the (scope, scopeId, toolName) tuple,
     * stamps {@code expectedRecoveryAt}, appends history. Creates the
     * document when none exists.
     */
    public ToolHealthDocument markUnavailable(
            String tenantId,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            ToolHealthClassification classification,
            @Nullable Instant expectedRecoveryAt,
            @Nullable String note,
            String by) {
        return changeStatus(
                tenantId, scope, scopeId, toolName,
                ToolHealthStatus.DOWN, classification, expectedRecoveryAt, note, by);
    }

    /**
     * Sets {@code status=DEGRADED}. Same shape as {@link #markUnavailable}
     * but for sporadic problems where calls still sometimes succeed.
     */
    public ToolHealthDocument markDegraded(
            String tenantId,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            ToolHealthClassification classification,
            @Nullable Instant expectedRecoveryAt,
            @Nullable String note,
            String by) {
        return changeStatus(
                tenantId, scope, scopeId, toolName,
                ToolHealthStatus.DEGRADED, classification, expectedRecoveryAt, note, by);
    }

    /**
     * Sets {@code status=OK}, clears {@code expectedRecoveryAt}, appends
     * a {@code WORKING} history entry. Use this after a successful probe
     * or a successful real tool invocation that proves the tool is back.
     */
    public ToolHealthDocument markAvailable(
            String tenantId,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            @Nullable String note,
            String by) {
        return changeStatus(
                tenantId, scope, scopeId, toolName,
                ToolHealthStatus.OK, ToolHealthClassification.WORKING,
                null, note, by);
    }

    private ToolHealthDocument changeStatus(
            String tenantId,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            ToolHealthStatus newStatus,
            ToolHealthClassification classification,
            @Nullable Instant expectedRecoveryAt,
            @Nullable String note,
            String by) {
        Instant now = Instant.now();
        ToolHealthDocument doc = fetchOrCreate(tenantId, scope, scopeId, toolName, now);

        boolean statusChanged = doc.getStatus() != newStatus;
        doc.setStatus(newStatus);
        if (statusChanged) {
            doc.setSince(now);
        }
        doc.setLastCheckedAt(now);
        doc.setExpectedRecoveryAt(expectedRecoveryAt);
        doc.setLastNote(note);
        doc.setLastClassification(classification);
        doc.setUpdatedAt(now);

        appendHistory(doc, ToolHealthHistoryEntry.builder()
                .at(now)
                .status(newStatus)
                .classification(classification)
                .note(note)
                .by(by)
                .expectedRecoveryAt(expectedRecoveryAt)
                .build());

        log.info("ToolHealth {} {} '{}' {} → {} by={} expectedRecoveryAt={}",
                scope, nullToEmpty(scopeId), toolName,
                classification, newStatus, by, expectedRecoveryAt);
        return repository.save(doc);
    }

    // ───────────────────────────────── Cooldown writes

    /**
     * Sets (or refreshes) a cooldown entry on the matching health
     * document. Existing entries with the same
     * {@code (errorSignature, userId)} are merged — {@code hits} bumps,
     * {@code nextSpawnAllowedAt} extends.
     */
    public ToolHealthCooldown setCooldown(
            String tenantId,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            String errorSignature,
            @Nullable String userId,
            ToolHealthClassification classification,
            Duration duration,
            @Nullable String note) {
        Instant now = Instant.now();
        ToolHealthDocument doc = fetchOrCreate(tenantId, scope, scopeId, toolName, now);

        ToolHealthCooldown existing = null;
        for (ToolHealthCooldown c : doc.getCooldowns()) {
            if (matches(c, errorSignature, userId)) {
                existing = c;
                break;
            }
        }
        Instant newDeadline = now.plus(duration);
        if (existing == null) {
            ToolHealthCooldown fresh = ToolHealthCooldown.builder()
                    .errorSignature(errorSignature)
                    .userId(userId)
                    .nextSpawnAllowedAt(newDeadline)
                    .hits(1)
                    .lastClassification(classification)
                    .lastTriggeredAt(now)
                    .note(note)
                    .build();
            doc.getCooldowns().add(fresh);
            doc.setUpdatedAt(now);
            repository.save(doc);
            return fresh;
        }
        existing.setNextSpawnAllowedAt(maxInstant(existing.getNextSpawnAllowedAt(), newDeadline));
        existing.setHits(existing.getHits() + 1);
        existing.setLastClassification(classification);
        existing.setLastTriggeredAt(now);
        if (note != null) existing.setNote(note);
        doc.setUpdatedAt(now);
        repository.save(doc);
        return existing;
    }

    /**
     * Removes any cooldown entry matching {@code (errorSignature, userId)}
     * on the matching health document. Used by the auto-clear path on
     * successful tool calls. No-op when nothing matches.
     */
    public void clearCooldown(
            String tenantId,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            String errorSignature,
            @Nullable String userId) {
        repository.findByTenantIdAndScopeAndScopeIdAndToolName(
                        tenantId, scope, nullToEmpty(scopeId), toolName)
                .ifPresent(doc -> {
                    boolean removed = removeMatching(doc, errorSignature, userId);
                    if (removed) {
                        doc.setUpdatedAt(Instant.now());
                        repository.save(doc);
                    }
                });
    }

    /**
     * Convenience for the dispatcher's success pre-hook. Walks all
     * cascade scopes for the tool and clears every cooldown that matches
     * the caller, plus flips any non-OK status entry in the narrowest
     * matching scope to {@code OK} (the tool just proved it works).
     */
    public void noteSuccessfulCall(
            String tenantId,
            @Nullable String sessionId,
            @Nullable String userId,
            @Nullable String projectId,
            String toolName) {
        Instant now = Instant.now();
        Map<ToolHealthScope, String> scopes = new LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) scopes.put(ToolHealthScope.SESSION, sessionId);
        if (userId != null    && !userId.isBlank())    scopes.put(ToolHealthScope.USER, userId);
        if (projectId != null && !projectId.isBlank()) scopes.put(ToolHealthScope.PROJECT, projectId);
        scopes.put(ToolHealthScope.TENANT, tenantId);
        scopes.put(ToolHealthScope.GLOBAL, "");

        boolean okFlipped = false;
        for (Map.Entry<ToolHealthScope, String> e : scopes.entrySet()) {
            Optional<ToolHealthDocument> maybe =
                    repository.findByTenantIdAndScopeAndScopeIdAndToolName(
                            tenantId, e.getKey(), nullToEmpty(e.getValue()), toolName);
            if (maybe.isEmpty()) continue;
            ToolHealthDocument doc = maybe.get();
            boolean changed = false;
            if (!okFlipped && doc.getStatus() != ToolHealthStatus.OK) {
                doc.setStatus(ToolHealthStatus.OK);
                doc.setSince(now);
                doc.setLastClassification(ToolHealthClassification.WORKING);
                doc.setExpectedRecoveryAt(null);
                doc.setLastCheckedAt(now);
                appendHistory(doc, ToolHealthHistoryEntry.builder()
                        .at(now)
                        .status(ToolHealthStatus.OK)
                        .classification(ToolHealthClassification.WORKING)
                        .by("auto-clear")
                        .note("Auto-cleared by successful invocation")
                        .build());
                okFlipped = true;
                changed = true;
            }
            // Clear any cooldown owned by this caller's userId or scope-wide.
            Iterator<ToolHealthCooldown> it = doc.getCooldowns().iterator();
            while (it.hasNext()) {
                ToolHealthCooldown c = it.next();
                if (c.getUserId() == null
                        || (userId != null && c.getUserId().equals(userId))) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) {
                doc.setUpdatedAt(now);
                doc.setLastCheckedAt(now);
                repository.save(doc);
            }
        }
    }

    // ───────────────────────────────── Internals

    private ToolHealthDocument fetchOrCreate(
            String tenantId,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            Instant now) {
        String key = nullToEmpty(scopeId);
        return repository
                .findByTenantIdAndScopeAndScopeIdAndToolName(tenantId, scope, key, toolName)
                .orElseGet(() -> ToolHealthDocument.builder()
                        .tenantId(tenantId)
                        .scope(scope)
                        .scopeId(key)
                        .toolName(toolName)
                        .status(ToolHealthStatus.OK)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
    }

    private static void appendHistory(ToolHealthDocument doc, ToolHealthHistoryEntry entry) {
        List<ToolHealthHistoryEntry> h = doc.getHistory();
        h.add(0, entry);
        while (h.size() > HISTORY_MAX_ENTRIES) {
            h.remove(h.size() - 1);
        }
    }

    private static Criteria scopeMatch(ToolHealthScope scope, String scopeId) {
        return Criteria.where(F_SCOPE).is(scope)
                .and(F_SCOPE_ID).is(nullToEmpty(scopeId));
    }

    private static int scopeRank(ToolHealthScope scope) {
        return switch (scope) {
            case SESSION -> 0;
            case USER -> 1;
            case PROJECT -> 2;
            case TENANT -> 3;
            case GLOBAL -> 4;
        };
    }

    private static boolean matches(ToolHealthCooldown c, String signature, @Nullable String userId) {
        if (!signature.equals(c.getErrorSignature())) return false;
        if (c.getUserId() == null) return userId == null;
        return c.getUserId().equals(userId);
    }

    private static boolean removeMatching(
            ToolHealthDocument doc, String signature, @Nullable String userId) {
        boolean removed = false;
        Iterator<ToolHealthCooldown> it = doc.getCooldowns().iterator();
        while (it.hasNext()) {
            if (matches(it.next(), signature, userId)) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static Instant maxInstant(@Nullable Instant a, Instant b) {
        if (a == null) return b;
        return a.isAfter(b) ? a : b;
    }
}
