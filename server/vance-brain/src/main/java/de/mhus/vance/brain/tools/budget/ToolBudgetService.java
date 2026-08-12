package de.mhus.vance.brain.tools.budget;

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
import java.util.concurrent.ConcurrentHashMap;
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
 * {@link #LIMIT_CACHE_TTL} against the inputs that can change it
 * (scope, model spec, fallback list) and invalidated immediately when the
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

    private final AiModelResolver aiModelResolver;
    private final ModelCatalog modelCatalog;
    private final ObservedToolLimitRegistry observedLimits;
    private final ToolUsageService toolUsageService;
    private final ToolBudgetProperties properties;
    private final Map<String, CachedLimit> limitCache = new ConcurrentHashMap<>();

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
     *                          cross-project workers)
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
        OptionalInt limit = limitFor(process, projectId);
        if (limit.isEmpty()) {
            return ToolBudget.UNLIMITED;
        }
        int reserved = Math.max(0, properties.getExternalReserve())
                + Math.max(0, properties.getActivationHeadroom());
        Map<String, Long> demand =
                toolUsageService.demandByTool(process.getTenantId(), projectId);
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
     */
    public OptionalInt limitFor(ThinkProcessDocument process, @Nullable String projectId) {
        String spec = ChatBehaviorBuilder.readModelSpec(process);
        List<String> fallbacks = ChatBehaviorBuilder.readFallbackAliases(process);
        String cacheKey = process.getTenantId() + "|" + projectId + "|" + spec
                + "|" + String.join(",", fallbacks);
        long version = observedLimits.version();
        Instant now = Instant.now();
        CachedLimit cached = limitCache.get(cacheKey);
        if (cached != null
                && cached.version() == version
                && cached.readAt().plus(LIMIT_CACHE_TTL).isAfter(now)) {
            return cached.limit();
        }
        OptionalInt resolved = resolveChainLimit(process, projectId, spec, fallbacks);
        limitCache.put(cacheKey, new CachedLimit(resolved, version, now));
        return resolved;
    }

    private OptionalInt resolveChainLimit(
            ThinkProcessDocument process,
            @Nullable String projectId,
            @Nullable String spec,
            List<String> fallbacks) {
        List<String> specs = new ArrayList<>();
        specs.add(spec);
        specs.addAll(fallbacks);
        int min = Integer.MAX_VALUE;
        for (String entry : specs) {
            OptionalInt entryLimit = limitForSpec(process, projectId, entry);
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
            ThinkProcessDocument process, @Nullable String projectId, @Nullable String spec) {
        AiModelResolver.Resolved resolved;
        try {
            resolved = aiModelResolver.resolveOrDefault(
                    spec, process.getTenantId(), projectId, process.getId());
        } catch (RuntimeException e) {
            log.trace("ToolBudgetService: cannot resolve model spec '{}' for process '{}': {}",
                    spec, process.getId(), e.toString());
            return OptionalInt.empty();
        }
        String label = resolved.providerInstance() + ":" + resolved.modelName();
        Integer configured = null;
        try {
            ModelInfo info = modelCatalog.lookupOrDefault(
                    process.getTenantId(), projectId,
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
}
