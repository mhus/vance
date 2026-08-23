package de.mhus.vance.brain.tools.budget;

import de.mhus.vance.brain.ai.AiConfigScope;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.toolusage.ToolUsageService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the tool-surface budget for a process: how many tool schemas
 * this turn's request may carry, plus the measured signals that order the
 * candidates.
 *
 * <p><b>Minimum over the whole chain.</b> The surface is built once per
 * turn, but {@code ResilientStreamingChatModel} may advance to a fallback
 * entry afterwards. A budget that only knew the primary model would
 * produce a manifest the fallback rejects — which is exactly how the
 * 2026-08-12 incident burned both chain entries on the same 400. The
 * limit is therefore {@code min} over primary and every configured
 * fallback, with a learned {@link ObservedToolLimitRegistry} value
 * capping each entry.
 *
 * <p><b>Memoised per turn-ish.</b> Resolving the chain reads the alias
 * cascade from settings, and {@code tools()} is called once per
 * action-loop iteration. The result is cached for
 * {@link #LIMIT_CACHE_TTL} against every input that can change it
 * (tenant, project <em>and</em> process — all three are settings layers
 * the alias resolves through — plus model spec and fallback list), bounded
 * by {@link #LIMIT_CACHE_MAX}, and invalidated immediately when the
 * observed-limit registry learns something new.
 *
 * <p>Fail-open throughout: any resolution error means "no known limit",
 * never a broken turn. The worst case is the provider 400 we had before.
 */
@Service
@Slf4j
public class ToolBudgetService {

    /** How long a resolved limit is reused before the cascade is read again. */
    static final Duration LIMIT_CACHE_TTL = Duration.ofMinutes(2);

    /**
     * Upper bound on memoised limits — least-recently-used evicted past
     * this. The TTL decides whether an entry is still <em>valid</em>, not
     * how many may exist: an expired entry is overwritten but never
     * dropped on its own, and the key carries the project, of which a
     * brain has one per user ({@code _user_<login>}). Same construction
     * and same reasoning as {@code ToolUsageService.READ_CACHE_MAX}.
     */
    static final int LIMIT_CACHE_MAX = 512;

    private final AiModelResolver aiModelResolver;
    private final ModelCatalog modelCatalog;
    private final ObservedToolLimitRegistry observedLimits;
    private final ToolUsageService toolUsageService;
    private final ToolBudgetProperties properties;
    // Access-order LRU behind a synchronized wrapper: get() reorders and
    // put() may evict, so both mutate and a plain map would not survive
    // the concurrent turns that read it.
    private final Map<String, CachedLimit> limitCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, CachedLimit>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedLimit> eldest) {
                    return size() > LIMIT_CACHE_MAX;
                }
            });

    public ToolBudgetService(
            AiModelResolver aiModelResolver,
            ModelCatalog modelCatalog,
            ObservedToolLimitRegistry observedLimits,
            ToolUsageService toolUsageService,
            ToolBudgetProperties properties) {
        this.aiModelResolver = aiModelResolver;
        this.modelCatalog = modelCatalog;
        this.observedLimits = observedLimits;
        this.toolUsageService = toolUsageService;
        this.properties = properties;
    }

    /**
     * Build the budget for {@code process}.
     *
     * @param projectId         the process's effective project (may differ
     *                          from {@code process.getProjectId()} for
     *                          cross-project workers). Used for the
     *                          <em>demand</em> counters only — the cap is
     *                          read through the process's own AI scope,
     *                          see {@link #limitFor}
     * @param activationRecency {@code toolName → activation timestamp}
     *                          from {@code ThinkProcessDocument}
     * @return a budget; {@link ToolBudget#UNLIMITED} when no cap is known
     *         or the feature is switched off
     */
    public ToolBudget forProcess(
            ThinkProcessDocument process,
            @Nullable String projectId,
            Map<String, Instant> activationRecency) {
        if (!properties.isEnabled()) {
            return ToolBudget.UNLIMITED;
        }
        OptionalInt limit = limitFor(process);
        if (limit.isEmpty()) {
            return ToolBudget.UNLIMITED;
        }
        int reserved = Math.max(0, properties.getExternalReserve())
                + Math.max(0, properties.getActivationHeadroom());
        // Demand is read per role: the ordering inside a class should
        // reflect what *this* recipe needs, not what the busiest worker in
        // the project happens to call.
        Map<String, Long> demand = toolUsageService.demandByTool(
                process.getTenantId(), projectId, ToolUsageService.roleOf(process));
        return new ToolBudget(
                limit.getAsInt(),
                reserved,
                activationRecency == null ? Map.of() : activationRecency,
                demand,
                properties.getMaxActivatedTools());
    }

    /** Family-level priority overrides from {@code vance.tools.budget.*}. */
    public ToolTriage.Hints familyHints() {
        return new ToolTriage.Hints(
                Set.of(), Set.of(), Set.of(),
                toSet(properties.getKeepFamilies()),
                toSet(properties.getDropFirstFamilies()));
    }

    /**
     * The effective cap for this process — {@code min} over the chain, or
     * empty when no entry declares one.
     *
     * <p>Takes no project: the AI scope is the process's own
     * ({@code process.getProjectId()}), because that is the one
     * {@link ChatBehaviorBuilder#fromProcess} and {@code EngineChatFactory}
     * resolve the endpoint and the catalog through. A cross-project worker
     * carries its <em>working</em> project elsewhere, and reading the cap
     * through that layer could budget a model the request never reaches.
     */
    public OptionalInt limitFor(ThinkProcessDocument process) {
        String spec = ChatBehaviorBuilder.readModelSpec(process);
        List<String> fallbacks = ChatBehaviorBuilder.readFallbackAliases(process);
        // Same settings view the chat itself will be built from: a
        // tenant-pinned process (params.aiScope) resolves alias, endpoint
        // and catalog from _tenant only. Reading the cap through the
        // project cascade instead could land on a different model than the
        // one the request will actually be sent to — and then budget the
        // wrong endpoint's limit. See ChatBehaviorBuilder.fromProcess.
        ScopeView scope = scopeFor(process);
        // The process id belongs in the key because it is a settings layer
        // of its own (SCOPE_THINK_PROCESS, writable through the admin API)
        // and limitForSpec resolves the alias through it. Without it, two
        // processes in one project that pin different models would hand
        // each other the wrong cap for LIMIT_CACHE_TTL — either an
        // unnecessary cut or the provider 400 the budget exists to avoid.
        String cacheKey = process.getTenantId() + "|" + scope.projectId()
                + "|" + scope.processId() + "|" + spec
                + "|" + String.join(",", fallbacks);
        long version = observedLimits.version();
        Instant now = Instant.now();
        CachedLimit cached = limitCache.get(cacheKey);
        if (cached != null
                && cached.version() == version
                && cached.readAt().plus(LIMIT_CACHE_TTL).isAfter(now)) {
            return cached.limit();
        }
        OptionalInt resolved = resolveChainLimit(process, scope, spec, fallbacks);
        limitCache.put(cacheKey, new CachedLimit(resolved, version, now));
        return resolved;
    }

    /**
     * The settings scope the AI config of this process resolves from —
     * the full cascade, or the {@code _tenant} layer alone when the recipe
     * pins it via {@code params.aiScope}. Pinning is expressed the same
     * way {@link ChatBehaviorBuilder} expresses it: {@code null} for both
     * inner scopes collapses the cascade to its base layer.
     */
    private static ScopeView scopeFor(ThinkProcessDocument process) {
        boolean pinned = ChatBehaviorBuilder.readAiConfigScope(process) == AiConfigScope.TENANT;
        return pinned
                ? new ScopeView(null, null)
                : new ScopeView(process.getProjectId(), process.getId());
    }

    private OptionalInt resolveChainLimit(
            ThinkProcessDocument process,
            ScopeView scope,
            @Nullable String spec,
            List<String> fallbacks) {
        List<String> specs = new ArrayList<>();
        specs.add(spec);
        specs.addAll(fallbacks);
        int min = Integer.MAX_VALUE;
        for (String entry : specs) {
            OptionalInt entryLimit = limitForSpec(process, scope, entry);
            if (entryLimit.isEmpty()) continue;
            min = Math.min(min, entryLimit.getAsInt());
        }
        return min == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(min);
    }

    /**
     * Cap for one chain entry: the catalog value, tightened by anything
     * the endpoint taught us at runtime. Either source alone is enough —
     * a learned limit works without catalog metadata, and vice versa.
     */
    private OptionalInt limitForSpec(
            ThinkProcessDocument process, ScopeView scope, @Nullable String spec) {
        AiModelResolver.Resolved resolved;
        try {
            resolved = aiModelResolver.resolveOrDefault(
                    spec, process.getTenantId(), scope.projectId(), scope.processId());
        } catch (RuntimeException e) {
            log.trace("ToolBudgetService: cannot resolve model spec '{}' for process '{}': {}",
                    spec, process.getId(), e.toString());
            return OptionalInt.empty();
        }
        String label = resolved.providerInstance() + ":" + resolved.modelName();
        Integer configured = null;
        try {
            ModelInfo info = modelCatalog.lookupOrDefault(
                    process.getTenantId(), scope.projectId(),
                    resolved.providerInstance(), resolved.provider(), resolved.modelName());
            configured = info.maxTools();
        } catch (RuntimeException e) {
            log.trace("ToolBudgetService: catalog lookup failed for '{}': {}", label, e.toString());
        }
        OptionalInt observed = observedLimits.observedFor(label);
        if (configured != null && configured > 0 && observed.isPresent()) {
            return OptionalInt.of(Math.min(configured, observed.getAsInt()));
        }
        if (configured != null && configured > 0) {
            return OptionalInt.of(configured);
        }
        return observed;
    }

    private static Set<String> toSet(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) out.add(v.trim());
        }
        return Set.copyOf(out);
    }

    /** Test hook — drop the memoised limits. */
    public void invalidateCache() {
        limitCache.clear();
    }

    private record CachedLimit(OptionalInt limit, long version, Instant readAt) {}

    /** Inner settings scopes to read the AI config through — both null = tenant layer only. */
    private record ScopeView(@Nullable String projectId, @Nullable String processId) {}
}
