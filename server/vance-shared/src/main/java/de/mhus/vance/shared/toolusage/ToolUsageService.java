package de.mhus.vance.shared.toolusage;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Data owner for {@link ToolUsageDocument}. Two write paths (a tool ran /
 * a tool's schema was looked up) and one read path (counters for a
 * project and role, used to order tools inside a priority class).
 *
 * <p><b>Per role, not per project.</b> Every method takes the recipe the
 * process runs under. Demand is role-specific — a coding worker calling
 * {@code file_read} 153 times says nothing about what the chat
 * orchestrator in the same project needs — and a shared pool would let
 * the busiest worker train everyone else's ranking.
 *
 * <p><b>Writes are best-effort.</b> A counter is a ranking hint; losing
 * one must never fail the tool call that produced it. Every write catches
 * and logs at trace level.
 *
 * <p><b>Reads are memoised.</b> The triage runs on every turn — for some
 * engines on every action-loop iteration — while these counters move
 * slowly and only break ties. A short TTL keeps the hot path off Mongo
 * without making the ranking stale in any way that matters. The memo is
 * a <em>bounded</em> LRU: a TTL alone never removes an entry, so a brain
 * serving many tenants × projects × roles would accumulate one snapshot
 * per combination for the lifetime of the pod.
 */
@Service
@Slf4j
public class ToolUsageService {

    /** How long a per-project/role counter snapshot is reused. */
    static final Duration READ_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Upper bound on memoised snapshots — least-recently-used evicted past
     * this. The TTL decides whether an entry is still <em>valid</em>; this
     * decides how many may exist at all, because an expired entry is
     * overwritten but never dropped on its own.
     */
    static final int READ_CACHE_MAX = 512;

    /**
     * Role bucket for writes that cannot be attributed — no recipe and no
     * engine name available. Its own bucket rather than a shared one, so
     * unattributed traffic can never pollute a real role's ranking.
     */
    public static final String ROLE_UNKNOWN = "_unknown";

    private static final String F_TENANT = "tenantId";
    private static final String F_PROJECT = "projectId";
    private static final String F_RECIPE = "recipeName";
    private static final String F_TOOL = "toolName";

    private final MongoTemplate mongoTemplate;
    private final ToolUsageRepository repository;
    // Access-order LRU behind a synchronized wrapper: both get() (which
    // reorders) and put() (which may evict) mutate, so a plain map would
    // not be safe across the concurrent turns that read it.
    private final Map<String, Snapshot> readCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
                    return size() > READ_CACHE_MAX;
                }
            });

    public ToolUsageService(MongoTemplate mongoTemplate, ToolUsageRepository repository) {
        this.mongoTemplate = mongoTemplate;
        this.repository = repository;
    }

    /** A tool ran successfully. */
    public void recordCall(
            @Nullable String tenantId, @Nullable String projectId, @Nullable String recipeName,
            @Nullable String toolName, @Nullable String family) {
        increment(tenantId, projectId, recipeName, toolName, family, "calls", "lastCallAt");
    }

    /** A tool's schema was handed out through {@code tool_description}. */
    public void recordDiscovery(
            @Nullable String tenantId, @Nullable String projectId, @Nullable String recipeName,
            @Nullable String toolName, @Nullable String family) {
        increment(tenantId, projectId, recipeName, toolName, family,
                "discoveryHits", "lastDiscoveryAt");
    }

    private void increment(
            @Nullable String tenantId, @Nullable String projectId, @Nullable String recipeName,
            @Nullable String toolName, @Nullable String family,
            String counterField, String timestampField) {
        if (isBlank(tenantId) || isBlank(projectId) || isBlank(toolName)) {
            return;
        }
        String role = role(recipeName);
        try {
            Query q = new Query(Criteria.where(F_TENANT).is(tenantId)
                    .and(F_PROJECT).is(projectId)
                    .and(F_RECIPE).is(role)
                    .and(F_TOOL).is(toolName));
            Update u = new Update()
                    .inc(counterField, 1L)
                    .set(timestampField, Instant.now())
                    .setOnInsert(F_TENANT, tenantId)
                    .setOnInsert(F_PROJECT, projectId)
                    .setOnInsert(F_RECIPE, role)
                    .setOnInsert(F_TOOL, toolName);
            if (!isBlank(family)) {
                u.set("family", family);
            }
            mongoTemplate.upsert(q, u, ToolUsageDocument.class);
        } catch (RuntimeException e) {
            // Ranking hint only — never let it break the caller.
            log.trace("ToolUsageService: {} increment failed for tenant='{}' project='{}' "
                            + "recipe='{}' tool='{}': {}",
                    counterField, tenantId, projectId, role, toolName, e.toString());
        }
    }

    /**
     * Combined demand per tool for one project and role:
     * {@code calls + discoveryHits}. One number because the consumer only
     * needs an ordering, and both events mean the same thing there ("this
     * role wanted this tool").
     *
     * <p>Returns an empty map on any read failure — a missing tie-breaker
     * degrades the ranking, it doesn't break it. Same for a role with no
     * history: no signal, so the declared order decides alone.
     */
    public Map<String, Long> demandByTool(
            @Nullable String tenantId, @Nullable String projectId, @Nullable String recipeName) {
        if (isBlank(tenantId) || isBlank(projectId)) {
            return Map.of();
        }
        String role = role(recipeName);
        String key = tenantId + " " + projectId + " " + role;
        Snapshot cached = readCache.get(key);
        Instant now = Instant.now();
        if (cached != null && cached.readAt().plus(READ_CACHE_TTL).isAfter(now)) {
            return cached.demand();
        }
        Map<String, Long> demand;
        try {
            List<ToolUsageDocument> docs = repository
                    .findByTenantIdAndProjectIdAndRecipeName(tenantId, projectId, role);
            Map<String, Long> acc = new LinkedHashMap<>();
            for (ToolUsageDocument doc : docs) {
                if (doc.getToolName() == null) continue;
                acc.put(doc.getToolName(), doc.getCalls() + doc.getDiscoveryHits());
            }
            demand = Map.copyOf(acc);
        } catch (RuntimeException e) {
            log.trace("ToolUsageService: demand read failed for tenant='{}' project='{}' "
                    + "recipe='{}': {}", tenantId, projectId, role, e.toString());
            demand = Map.of();
        }
        readCache.put(key, new Snapshot(demand, now));
        return demand;
    }

    /**
     * All counter rows of a project, every role included — the insights
     * view. Not memoised: an operator looking at the tab wants the current
     * numbers, and this is not a hot path.
     */
    public List<ToolUsageDocument> listByProject(
            @Nullable String tenantId, @Nullable String projectId) {
        if (isBlank(tenantId) || isBlank(projectId)) {
            return List.of();
        }
        try {
            return repository.findByTenantIdAndProjectId(tenantId, projectId);
        } catch (RuntimeException e) {
            log.trace("ToolUsageService: listByProject failed for tenant='{}' project='{}': {}",
                    tenantId, projectId, e.toString());
            return List.of();
        }
    }

    /** Drops the memoised read snapshots — tests and admin refresh. */
    public void invalidateCache() {
        readCache.clear();
    }

    /**
     * Role a process's tool usage is attributed to: its recipe name, or the
     * engine name when the process carries none (legacy spawns, direct
     * engine invocations).
     *
     * <p><b>The single derivation site.</b> Writers ({@code ThinkEngineService},
     * {@code ToolDescriptionTool}) and the reader ({@code ToolBudgetService})
     * have to agree on this key or the triage reads past its own counters —
     * so they all call here rather than each spelling out the rule.
     *
     * @return the role, or {@code null} when neither field is set; the write
     *         paths turn that into {@link #ROLE_UNKNOWN}
     */
    public static @Nullable String roleOf(@Nullable ThinkProcessDocument process) {
        if (process == null) return null;
        String recipe = process.getRecipeName();
        if (!isBlank(recipe)) return recipe.trim();
        String engine = process.getThinkEngine();
        return isBlank(engine) ? null : engine.trim();
    }

    private static String role(@Nullable String recipeName) {
        return isBlank(recipeName) ? ROLE_UNKNOWN : recipeName.trim();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private record Snapshot(Map<String, Long> demand, Instant readAt) {}
}
