package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.api.insights.FacetInsightsDto;
import de.mhus.vance.api.insights.FacetValueInsightsDto;
import de.mhus.vance.api.insights.ZarniwoopInsightsDto;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.facet.Facet;
import de.mhus.vance.toolpack.facet.FacetValue;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Assembles the {@link ZarniwoopInsightsDto} list for the
 * insights-admin REST endpoint. Pulls together
 * {@code SearchProviderFactory} (which instances exist),
 * {@link ZarniwoopUsageCounter} (how often each got called) and
 * {@link ToolHealthService} (active cooldowns per modality), so the UI
 * tab gets one ready-shaped row per instance.
 *
 * <p>Per spec ({@code planning/zarniwoop-service.md} §3a): instances
 * are project-scoped. This service rejects calls without a project and
 * never falls back to a tenant-wide list.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZarniwoopInsightsService {

    private final SearchProviderFactory factory;
    private final ZarniwoopUsageCounter usageCounter;
    private final ToolHealthService healthService;
    private final ZarniwoopGateService gate;

    public List<ZarniwoopInsightsDto> listInstances(String tenantId, String projectId) {
        return listInstances(tenantId, projectId, /* refresh */ false);
    }

    /**
     * The provider instances of a project. With {@code refresh} the factory
     * cache is dropped first, which is what an operator needs right after
     * writing {@code research.endpoint.*} — until then the list is up to five
     * minutes stale and looks exactly like a wrong setting key.
     */
    public List<ZarniwoopInsightsDto> listInstances(
            String tenantId, String projectId, boolean refresh) {
        if (refresh) {
            factory.evict(new SearchScope(tenantId, projectId, null, null));
        }
        return assembleInstances(tenantId, projectId);
    }

    private List<ZarniwoopInsightsDto> assembleInstances(String tenantId, String projectId) {
        SearchScope scope = new SearchScope(tenantId, projectId, null, null);
        List<SearchProviderInstance> instances;
        try {
            instances = factory.assemble(scope);
        } catch (ZarniwoopException e) {
            // Project without endpoints — return empty rather than 500.
            log.debug("ZarniwoopInsightsService: assemble for '{}/{}' raised: {}",
                    tenantId, projectId, e.toString());
            return List.of();
        }
        List<ZarniwoopInsightsDto> out = new ArrayList<>(instances.size());
        for (SearchProviderInstance inst : instances) {
            out.add(describe(scope, inst));
        }
        return out;
    }

    private ZarniwoopInsightsDto describe(SearchScope scope, SearchProviderInstance inst) {
        ProviderAvailability avail;
        try {
            avail = inst.availability(scope);
        } catch (RuntimeException e) {
            avail = ProviderAvailability.DISABLED;
        }

        String statusText = null;
        try {
            statusText = inst.statusText(scope);
        } catch (RuntimeException e) {
            log.debug("statusText raised for instance '{}': {}", inst.id(), e.toString());
        }

        ZarniwoopUsageCounter.Snapshot snap = usageCounter.snapshotFor(
                scope.tenantId(), scope.projectId(), inst.id());

        // Look up cooldowns per modality and pick the first that's active.
        Optional<ActiveCooldown> activeCooldown = findActiveCooldown(scope, inst);
        if (activeCooldown.isPresent()) {
            // Effective availability widens — the dispatcher would skip
            // this instance, so surface it even when availability()
            // says READY.
            avail = ProviderAvailability.COOLDOWN;
        }

        // Gate decision — operator settings + UI override.
        ZarniwoopGateService.GateDecision gateDecision = gate.resolve(scope, inst.id());
        // When the operator gate forces the instance off, surface it as
        // DISABLED in the availability column so the UI badge agrees
        // with effectivelyEnabled.
        if (!gateDecision.effectivelyEnabled()) {
            avail = ProviderAvailability.DISABLED;
        }

        return ZarniwoopInsightsDto.builder()
                .id(inst.id())
                .displayName(inst.displayName())
                .protocol(extractProtocolId(inst))
                .modalities(sortedNames(inst.modalities()))
                .domains(sortedNames(inst.domains()))
                .tiers(sortedNames(inst.tiers()))
                .facets(facetDtos(inst.facets()))
                .availability(avail.name())
                .statusText(statusText)
                .callCount(snap.total())
                .okCount(snap.ok())
                .errorCount(snap.error())
                .lastUsedAt(isoOrNull(snap.lastUsedAt()))
                .lastErrorAt(isoOrNull(snap.lastErrorAt()))
                .lastErrorMessage(snap.lastErrorMessage())
                .activeCooldownSignature(activeCooldown
                        .map(c -> c.cooldown().getErrorSignature())
                        .orElse(null))
                .activeCooldownSubject(activeCooldown
                        .map(ActiveCooldown::subject)
                        .orElse(null))
                .activeCooldownUntil(activeCooldown
                        .map(c -> isoOrNull(c.cooldown().getNextSpawnAllowedAt()))
                        .orElse(null))
                .defaultEnabled(gateDecision.defaultEnabled())
                .manualOverride(gateDecision.override()
                        .map(Enum::name).orElse(null))
                .effectivelyEnabled(gateDecision.effectivelyEnabled())
                .build();
    }

    /**
     * A cooldown plus the subject it was found under. The subject is
     * per-modality, so the caller cannot reconstruct it from the
     * instance id — and the UI needs it to clear the cooldown.
     */
    private record ActiveCooldown(String subject, ToolHealthCooldown cooldown) {}

    private Optional<ActiveCooldown> findActiveCooldown(
            SearchScope scope, SearchProviderInstance inst) {
        Instant now = Instant.now();
        for (SearchModality m : inst.modalities()) {
            String subject = ZarniwoopSettings.cooldownSubject(inst.id(), m);
            Optional<ToolHealthCooldown> cd = healthService.lookupActiveCooldown(
                    scope.tenantId(),
                    ToolHealthScope.PROJECT,
                    scope.projectId(),
                    subject,
                    /* errorSignature */ null,
                    /* userId */ null,
                    now);
            if (cd.isPresent()) {
                return Optional.of(new ActiveCooldown(subject, cd.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Best-effort: derive the protocol id from the instance display
     * name (Serper/Wikipedia subclass on display "Serper (serper-main)").
     * Falls back to {@code "?"} for instances that don't follow the
     * convention. The factory does not expose a structured
     * protocol-id link today — when the SPI grows one, switch to it.
     */
    private static String extractProtocolId(SearchProviderInstance inst) {
        String name = inst.displayName();
        if (name == null || name.isEmpty()) return "?";
        int paren = name.indexOf('(');
        if (paren > 0) return name.substring(0, paren).trim().toLowerCase();
        return name.toLowerCase();
    }

    /**
     * The declared facets with their values — what a picker needs. Ids alone
     * would leave the reader with {@code m49:142} and no table to resolve it.
     */
    private static List<FacetInsightsDto> facetDtos(List<Facet> facets) {
        List<FacetInsightsDto> out = new ArrayList<>(facets.size());
        for (Facet facet : facets) {
            List<FacetValueInsightsDto> values =
                    new ArrayList<>(facet.values().size());
            for (FacetValue value : facet.values()) {
                values.add(FacetValueInsightsDto.builder()
                        .id(value.id())
                        .label(value.label())
                        .parentId(value.parentId())
                        .build());
            }
            out.add(FacetInsightsDto.builder()
                    .key(facet.key())
                    .label(facet.label())
                    .hierarchical(facet.hierarchical())
                    .lazyChildren(facet.lazyChildren())
                    .values(values)
                    .build());
        }
        return out;
    }

    private static List<String> sortedNames(java.util.Set<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) return List.of();
        TreeSet<String> names = new TreeSet<>();
        for (Enum<?> v : values) names.add(v.name());
        return new ArrayList<>(names);
    }

    private static String isoOrNull(Instant value) {
        return value == null ? null : value.toString();
    }
}
