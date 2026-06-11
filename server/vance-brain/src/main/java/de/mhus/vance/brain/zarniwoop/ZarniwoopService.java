package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.agrajag.AgrajagChecker;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import de.mhus.vance.api.toolhealth.ToolHealthClassification;
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

    public ZarniwoopService(
            SearchProviderFactory factory,
            SettingService settings,
            ToolHealthService healthService,
            ObjectProvider<AgrajagChecker> agrajagProvider,
            QuotaCache quotaCache,
            ZarniwoopUsageCounter usageCounter,
            ZarniwoopGateService gate) {
        this.factory = factory;
        this.settings = settings;
        this.healthService = healthService;
        this.agrajagProvider = agrajagProvider;
        this.quotaCache = quotaCache;
        this.usageCounter = usageCounter;
        this.gate = gate;
    }

    /**
     * Dispatch one search. The {@code ctx} is forwarded to Agrajag on
     * hard failures so cooldowns are tenant/project/user scoped.
     */
    public SearchResult search(SearchRequest req, SearchScope scope, ToolInvocationContext ctx) {
        if (req == null) {
            throw new ZarniwoopException("request is required");
        }
        if (StringUtils.isBlank(scope.projectId())) {
            throw new ZarniwoopException("research tools require a project scope");
        }

        List<SearchProviderInstance> ordered = resolveProviders(scope, req);
        if (ordered.isEmpty()) {
            return SearchResult.unavailable(req,
                    "no provider instance available for modality=" + req.modality());
        }

        SearchResult lastError = null;
        for (SearchProviderInstance instance : ordered) {
            try {
                SearchResult result = instance.search(req, scope);
                if (result != null && result.ok()) {
                    usageCounter.recordSuccess(scope, instance.id(), req.modality());
                    return result;
                }
                lastError = result;
                log.debug("Zarniwoop: instance '{}' returned soft failure: {}",
                        instance.id(),
                        result == null ? "(null result)" : result.errorMessage());
            } catch (Throwable t) {
                usageCounter.recordError(scope, instance.id(), req.modality(),
                        t.getMessage());
                handleHardFailure(instance, req, ctx, t);
            }
        }
        return lastError != null
                ? lastError
                : SearchResult.unavailable(req, "all candidate instances failed");
    }

    /**
     * Order candidate instances for the request: pinned instance first
     * (EXPERT-tier only), then default, then fallback, then implicit
     * candidates that simply support the modality. Filters out
     * unavailable / cooldown'd / wrong-tier entries.
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
                    .filter(p -> isUsable(p, scope, req.modality()))
                    .toList();
        }

        String defaultId = settings.getStringValueCascade(
                scope.tenantId(), scope.projectId(), scope.processId(),
                ZarniwoopSettings.defaultKey(req.modality()));
        List<String> fallbackIds = csv(settings.getStringValueCascade(
                scope.tenantId(), scope.projectId(), scope.processId(),
                ZarniwoopSettings.fallbackKey(req.modality())));

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
                .filter(p -> isUsable(p, scope, req.modality()))
                .toList();
    }

    private boolean isUsable(SearchProviderInstance instance,
                             SearchScope scope, SearchModality modality) {
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
            applyProactiveQuotaCooldown(instance, scope, modality, subject, q.get());
            return false;
        }
        return true;
    }

    private void applyProactiveQuotaCooldown(SearchProviderInstance instance,
                                             SearchScope scope,
                                             SearchModality modality,
                                             String subject,
                                             QuotaStatus quota) {
        Duration cooldown = quota.resetsAt() != null
                ? Duration.between(Instant.now(), quota.resetsAt())
                : Duration.ofHours(24);
        if (cooldown.isNegative() || cooldown.isZero()) cooldown = Duration.ofHours(1);
        try {
            healthService.setCooldown(
                    scope.tenantId(),
                    ToolHealthScope.PROJECT,
                    scope.projectId(),
                    subject,
                    /* errorSignature */ "proactive_quota_zero",
                    /* userId */ null,
                    ToolHealthClassification.TECHNICALLY_BROKEN,
                    cooldown,
                    "proactive: remaining=0"
                            + (quota.resetsAt() == null ? "" : ", resetsAt=" + quota.resetsAt()));
            log.debug("Zarniwoop: proactive quota cooldown set on '{}' for modality={} "
                    + "(scope project='{}/{}'), duration={}",
                    instance.id(), modality, scope.tenantId(), scope.projectId(), cooldown);
        } catch (RuntimeException e) {
            log.warn("Zarniwoop: setCooldown raised — proceeding without proactive lock: {}",
                    e.toString());
        }
    }

    private void handleHardFailure(SearchProviderInstance instance,
                                   SearchRequest req, ToolInvocationContext ctx,
                                   Throwable t) {
        log.warn("Zarniwoop: instance '{}' raised on modality={}: {}",
                instance.id(), req.modality(), t.toString());
        if (ctx == null) {
            return;
        }
        AgrajagChecker checker = agrajagProvider.getIfAvailable();
        if (checker == null) {
            return;
        }
        try {
            checker.handle(
                    ZarniwoopSettings.cooldownSubject(instance.id(), req.modality()),
                    t, ctx);
        } catch (RuntimeException agrajagFailure) {
            log.warn("Zarniwoop: Agrajag.handle raised — proceeding without classification: {}",
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
