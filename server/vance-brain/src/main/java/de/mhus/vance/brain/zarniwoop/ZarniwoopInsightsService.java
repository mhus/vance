package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.api.insights.ZarniwoopInsightsDto;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
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

    public List<ZarniwoopInsightsDto> listInstances(String tenantId, String projectId) {
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
        Optional<ToolHealthCooldown> activeCooldown = findActiveCooldown(scope, inst);
        if (activeCooldown.isPresent()) {
            // Effective availability widens — the dispatcher would skip
            // this instance, so surface it even when availability()
            // says READY.
            avail = ProviderAvailability.COOLDOWN;
        }

        return ZarniwoopInsightsDto.builder()
                .id(inst.id())
                .displayName(inst.displayName())
                .protocol(extractProtocolId(inst))
                .modalities(sortedNames(inst.modalities()))
                .domains(sortedNames(inst.domains()))
                .tiers(sortedNames(inst.tiers()))
                .availability(avail.name())
                .statusText(statusText)
                .callCount(snap.total())
                .okCount(snap.ok())
                .errorCount(snap.error())
                .lastUsedAt(isoOrNull(snap.lastUsedAt()))
                .lastErrorAt(isoOrNull(snap.lastErrorAt()))
                .lastErrorMessage(snap.lastErrorMessage())
                .activeCooldownSignature(activeCooldown
                        .map(ToolHealthCooldown::getErrorSignature)
                        .orElse(null))
                .activeCooldownUntil(activeCooldown
                        .map(c -> isoOrNull(c.getNextSpawnAllowedAt()))
                        .orElse(null))
                .build();
    }

    private Optional<ToolHealthCooldown> findActiveCooldown(
            SearchScope scope, SearchProviderInstance inst) {
        Instant now = Instant.now();
        for (SearchModality m : inst.modalities()) {
            Optional<ToolHealthCooldown> cd = healthService.lookupActiveCooldown(
                    scope.tenantId(),
                    ToolHealthScope.PROJECT,
                    scope.projectId(),
                    ZarniwoopSettings.cooldownSubject(inst.id(), m),
                    /* errorSignature */ null,
                    /* userId */ null,
                    now);
            if (cd.isPresent()) return cd;
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
