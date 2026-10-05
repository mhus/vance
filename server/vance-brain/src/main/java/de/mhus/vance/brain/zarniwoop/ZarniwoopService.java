package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.agrajag.AgrajagChecker;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.facet.FacetSelection;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchQuotaExceededException;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The single entry point for Zarniwoop searches. Resolves the candidate
 * instance cascade for {@code (scope, modality, tier)}, dispatches the
 * request, and on hard failure hands the throwable to
 * {@link AgrajagChecker} so a cooldown is set on
 * {@code research:<instanceId>:<modality>} (scope PROJECT).
 *
 * <p>Proactive quota-zero gating and the
 * {@code ZarniwoopLogService} audit-doc writes are introduced in
 * later migration steps — this v1 dispatcher keeps the surface minimal.
 *
 * <p>{@link AgrajagChecker} is injected as an
 * {@link ObjectProvider} so the service stays usable in tests that
 * don't want to wire the full health-stack.
 */
@Service
@Slf4j
public class ZarniwoopService {

    private final SearchProviderFactory factory;
    private final SettingService settings;
    private final ToolHealthService healthService;
    private final ObjectProvider<AgrajagChecker> agrajagProvider;
    private final QuotaCache quotaCache;
    private final ZarniwoopUsageCounter usageCounter;
    private final ZarniwoopGateService gate;
    private final de.mhus.vance.brain.tools.ToolInterruptChecker interruptChecker;

    public ZarniwoopService(
            SearchProviderFactory factory,
            SettingService settings,
            ToolHealthService healthService,
            ObjectProvider<AgrajagChecker> agrajagProvider,
            QuotaCache quotaCache,
            ZarniwoopUsageCounter usageCounter,
            ZarniwoopGateService gate,
            de.mhus.vance.brain.tools.ToolInterruptChecker interruptChecker) {
        this.factory = factory;
        this.settings = settings;
        this.healthService = healthService;
        this.agrajagProvider = agrajagProvider;
        this.quotaCache = quotaCache;
        this.usageCounter = usageCounter;
        this.gate = gate;
        this.interruptChecker = interruptChecker;
    }

    /**
     * Dispatch one search. The {@code ctx} is forwarded to Agrajag on
     * hard failures so cooldowns are tenant/project/user scoped.
     */
    public SearchResult search(SearchRequest req, SearchScope scope, ToolInvocationContext ctx) {
        // Mid-operation interrupt: bail on ESC / /pause before hitting a
        // provider. Covers direct research_search / research_rich and each
        // parallel search dispatched by research_investigate.
        interruptChecker.throwIfHalted(ctx == null ? null : ctx.processId());
        if (req == null) {
            throw new ZarniwoopException("request is required");
        }
        if (StringUtils.isBlank(scope.projectId())) {
            throw new ZarniwoopException("research tools require a project scope");
        }

        List<SearchProviderInstance> ordered = resolveProviders(scope, req);
        if (ordered.isEmpty()) {
            return SearchResult.unavailable(req, "no provider instance available for modality=" + req.modality());
        }

        SearchResult lastError = null;
        SearchResult firstEmpty = null;
        for (SearchProviderInstance instance : ordered) {
            try {
                SearchResult result = instance.search(req, scope);
                if (result != null && result.ok()) {
                    // The call happened and cost quota, empty or not.
                    usageCounter.recordSuccess(scope, instance.id(), req.modality());
                    // An empty answer is not a failure (sources guarantee that),
                    // but it must not stop the cascade either: a default that
                    // knows nothing must not starve the fallbacks behind it.
                    // First non-empty answer wins; when every candidate comes
                    // back empty, the first empty answer is the honest result.
                    if (result.hits().isEmpty()) {
                        if (firstEmpty == null) {
                            firstEmpty = result;
                        }
                        continue;
                    }
                    return result;
                }
                lastError = result;
                log.debug(
                        "Zarniwoop: instance '{}' returned soft failure: {}",
                        instance.id(),
                        result == null ? "(null result)" : result.errorMessage());
            } catch (SearchQuotaExceededException quota) {
                // Known, terminating state — not a failure to analyse and not
                // an Agrajag case. Cool the instance down until its quota
                // returns and fall through to the next candidate.
                String note = "quota exhausted: " + quota.getMessage();
                usageCounter.recordError(scope, instance.id(), req.modality(), note);
                applyQuotaCooldown(instance, scope, req.modality(), quota.resetsAt(), "quota_exhausted", note);
            } catch (Throwable t) {
                usageCounter.recordError(scope, instance.id(), req.modality(), t.getMessage());
                handleHardFailure(instance, req, ctx, t);
            }
        }
        return firstEmpty != null
                ? firstEmpty
                : lastError != null ? lastError : SearchResult.unavailable(req, "all candidate instances failed");
    }

    /**
     * Order candidate instances for the request: pinned instance first
     * (EXPERT-tier only), then default, then fallback, then implicit
     * candidates that simply support the modality. The default/fallback
     * chain comes from the {@code research.default.*} /
     * {@code research.fallback.*} settings, or from the shipped chain in
     * {@link ZarniwoopSettings} when no setting was written — an id that is
     * not configured as a source document is skipped silently, so the
     * chain degrades instead of failing. Filters out unavailable /
     * cooldown'd / wrong-tier entries.
     */
    List<SearchProviderInstance> resolveProviders(SearchScope scope, SearchRequest req) {
        List<SearchProviderInstance> all = factory.assemble(scope);
        if (all.isEmpty()) return List.of();

        // EXPERT + pin: bypass cascade, use exactly that instance.
        if (req.tier() == SearchTier.EXPERT && !StringUtils.isBlank(req.pinnedProviderId())) {
            return all.stream()
                    .filter(p -> p.id().equals(req.pinnedProviderId()))
                    .filter(p -> p.modalities().contains(req.modality()))
                    .filter(p -> p.tiers().contains(req.tier()))
                    .filter(p -> answersSelectedFacets(p, req))
                    .filter(p -> isUsable(p, scope, req.modality()))
                    .toList();
        }

        String defaultId = settings.getStringValueCascade(
                scope.tenantId(), scope.projectId(), scope.processId(), ZarniwoopSettings.defaultKey(req.modality()));
        if (defaultId == null) {
            defaultId = ZarniwoopSettings.shippedDefault(req.modality());
        }
        // Absent setting → the shipped chain; an explicitly empty setting is
        // the operator saying "no chain, use the assemble order".
        String fallbackSetting = settings.getStringValueCascade(
                scope.tenantId(), scope.projectId(), scope.processId(), ZarniwoopSettings.fallbackKey(req.modality()));
        List<String> fallbackIds =
                csv(fallbackSetting != null ? fallbackSetting : ZarniwoopSettings.shippedFallbacks(req.modality()));
        Map<String, SearchProviderInstance> byId = new LinkedHashMap<>();
        for (SearchProviderInstance p : all) byId.put(p.id(), p);

        LinkedHashSet<SearchProviderInstance> ordered = new LinkedHashSet<>();
        if (defaultId != null && byId.containsKey(defaultId)) {
            ordered.add(byId.get(defaultId));
        }
        for (String id : fallbackIds) {
            SearchProviderInstance p = byId.get(id);
            if (p != null) ordered.add(p);
        }
        for (SearchProviderInstance p : all) {
            if (p.modalities().contains(req.modality())) ordered.add(p);
        }
        return ordered.stream()
                .filter(p -> p.modalities().contains(req.modality()))
                .filter(p -> p.tiers().contains(req.tier()))
                .filter(p -> answersSelectedFacets(p, req))
                .filter(p -> isUsable(p, scope, req.modality()))
                .toList();
    }

    /**
     * Whether this instance declared every facet the request selected.
     *
     * <p>A provider that did not is dropped from the candidate list rather
     * than asked and filtered afterwards. There is nothing to filter with: a
     * hit carries no facet values, and a search has no cursor to replace the
     * dropped ones from — a page of twenty would come back as three with no
     * way to fetch more. Silently answering the unrestricted question instead
     * would be worse still.
     */
    private boolean answersSelectedFacets(SearchProviderInstance instance, SearchRequest req) {
        if (req.facets().isEmpty()) {
            return true;
        }
        List<String> missing = FacetSelection.undeclaredKeys(req.facets(), FacetSelection.keysOf(instance.facets()));
        if (missing.isEmpty()) {
            return true;
        }
        log.debug("Skipping search instance '{}' — it does not declare facet(s) {}", instance.id(), missing);
        return false;
    }

    private boolean isUsable(SearchProviderInstance instance, SearchScope scope, SearchModality modality) {
        // Operator gate first — a setting or UI override that turned
        // the instance off short-circuits everything below.
        if (!gate.isEnabled(scope, instance.id())) {
            return false;
        }
        if (instance.availability(scope) != ProviderAvailability.READY) {
            return false;
        }
        String subject = ZarniwoopSettings.cooldownSubject(instance.id(), modality);
        Optional<ToolHealthCooldown> cooldown = healthService.lookupActiveCooldown(
                scope.tenantId(),
                ToolHealthScope.PROJECT,
                scope.projectId(),
                subject,
                /* errorSignature */ null,
                /* userId */ scope.userId(),
                Instant.now());
        if (cooldown.isPresent()) return false;

        // Proactive zero-quota gate. Instances without a quota endpoint
        // return Optional.empty() and are passed through.
        Optional<QuotaStatus> q = quotaCache.get(instance, scope);
        if (q.isPresent() && q.get().remaining() <= 0) {
            applyProactiveQuotaCooldown(instance, scope, modality, q.get());
            return false;
        }
        return true;
    }

    /** Proactive zero-quota gate entry point — {@link QuotaStatus} variant. */
    private void applyProactiveQuotaCooldown(
            SearchProviderInstance instance, SearchScope scope, SearchModality modality, QuotaStatus quota) {
        applyQuotaCooldown(
                instance,
                scope,
                modality,
                quota.resetsAt(),
                "proactive_quota_zero",
                "proactive: remaining=0" + (quota.resetsAt() == null ? "" : ", resetsAt=" + quota.resetsAt()));
    }

    /**
     * Cooldown for a <em>known-empty quota</em> — either observed proactively
     * ({@code remaining == 0}) or reported by the provider
     * ({@link SearchQuotaExceededException}). Runs until the quota comes back
     * ({@code resetsAt}, else 24h) and is deliberately not routed through
     * Agrajag: an empty quota is a terminating state, not a defect to analyse
     * — the provider is not "technically broken", it is spent. The cascade
     * continues with the next candidate while this holds.
     */
    private void applyQuotaCooldown(
            SearchProviderInstance instance,
            SearchScope scope,
            SearchModality modality,
            @Nullable Instant resetsAt,
            String errorSignature,
            String note) {
        Duration cooldown = resetsAt != null ? Duration.between(Instant.now(), resetsAt) : Duration.ofHours(24);
        if (cooldown.isNegative() || cooldown.isZero()) cooldown = Duration.ofHours(1);
        try {
            healthService.setCooldown(
                    scope.tenantId(),
                    ToolHealthScope.PROJECT,
                    scope.projectId(),
                    ZarniwoopSettings.cooldownSubject(instance.id(), modality),
                    errorSignature,
                    // Store under the same userId isUsable() looks up with — a
                    // null-user cooldown is only matched by a null-user lookup,
                    // so writing null here made the proactive zero-quota gate a
                    // no-op for the normal (non-null userId) call path.
                    /* userId */ scope.userId(),
                    ToolHealthClassification.TECHNICALLY_BROKEN,
                    cooldown,
                    note);
            log.debug(
                    "Zarniwoop: quota cooldown set on '{}' for modality={} " + "(scope project='{}/{}'), duration={}",
                    instance.id(),
                    modality,
                    scope.tenantId(),
                    scope.projectId(),
                    cooldown);
        } catch (RuntimeException e) {
            log.warn("Zarniwoop: setCooldown raised — proceeding without quota lock: {}", e.toString());
        }
    }

    private void handleHardFailure(
            SearchProviderInstance instance, SearchRequest req, ToolInvocationContext ctx, Throwable t) {
        log.warn("Zarniwoop: instance '{}' raised on modality={}: {}", instance.id(), req.modality(), t.toString());
        if (ctx == null) {
            return;
        }
        AgrajagChecker checker = agrajagProvider.getIfAvailable();
        if (checker == null) {
            return;
        }
        try {
            checker.handle(ZarniwoopSettings.cooldownSubject(instance.id(), req.modality()), t, ctx);
        } catch (RuntimeException agrajagFailure) {
            log.warn(
                    "Zarniwoop: Agrajag.handle raised — proceeding without classification: {}",
                    agrajagFailure.toString());
        }
    }

    static List<String> csv(String value) {
        if (StringUtils.isBlank(value)) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }
}
