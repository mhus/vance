package de.mhus.vance.brain.ai.discovery;

import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled model discovery — the automation nobody has to press a
 * button for. Auto-docs (existence, limits) and the {@code auto: true}
 * pricing docs are only useful when discovery actually runs, and the
 * manual trigger (Profile → Actions, the {@code ai_models_discover}
 * tool, the REST endpoint) requires someone to remember it. This tick
 * closes that gap for deployments that opt in: every tenant is
 * discovered periodically, so new models appear and prices stay current
 * without human action.
 *
 * <p><b>Opt-in</b> via {@code vance.ai-models.discovery.enabled} —
 * default <b>false</b>. A local dev brain does not want listing calls
 * against every configured endpoint every few hours; production sets
 * the flag. When disabled the tick exits before doing anything, the
 * manual triggers remain the only path.
 *
 * <p>Master-pod guarded: discovery writes documents, and only one pod
 * should run the pass at a time (the results would be identical — it
 * is about avoiding duplicate listing calls and racing upserts, not
 * correctness). In a single-pod deployment the local pod always holds
 * the lease, so the guard is a no-op there.
 *
 * <p>Failure isolation mirrors the service itself: one tenant's
 * broken endpoint (expired key, unreachable gateway) must not stop the
 * other tenants from being discovered; failures land in the log as
 * WARN and the next tick tries again.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ModelDiscoveryTick {

    private final ClusterMasterService masterService;
    private final TenantService tenantService;
    private final ModelDiscoveryService discoveryService;

    /** Opt-in switch — default off, see the class doc. */
    @Value("${vance.ai-models.discovery.enabled:false}")
    private boolean enabled;

    /**
     * Default: first pass 2 minutes after boot (after the catalog
     * bootstrapper settled), then every 6 hours. Tunable via
     * {@code vance.ai-models.discovery.interval} /
     * {@code vance.ai-models.discovery.initial-delay} (ISO-8601
     * durations).
     */
    @Scheduled(
            fixedDelayString = "${vance.ai-models.discovery.interval:PT6H}",
            initialDelayString = "${vance.ai-models.discovery.initial-delay:PT2M}")
    public void tick() {
        if (!enabled) {
            return;
        }
        if (!masterService.isLocalPodMaster()) {
            return;
        }
        List<TenantDocument> tenants = tenantService.all();
        int tenantsDone = 0;
        int tenantsFailed = 0;
        int modelsWritten = 0;
        int pricingDocs = 0;
        for (TenantDocument tenant : tenants) {
            String name = tenant.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                ModelDiscoveryService.DiscoveryResult result = discoveryService.discoverForTenant(name);
                tenantsDone++;
                modelsWritten += result.modelsWritten();
                pricingDocs += result.pricingDocsCreated() + result.pricingDocsUpdated();
            } catch (RuntimeException e) {
                tenantsFailed++;
                log.warn("ModelDiscoveryTick: discovery for tenant '{}' failed: {}", name, e.toString());
            }
        }
        if (tenantsDone + tenantsFailed > 0) {
            log.info(
                    "ModelDiscoveryTick: {} tenant(s) discovered, {} failed, {} model doc(s), {} pricing doc(s) created/updated",
                    tenantsDone,
                    tenantsFailed,
                    modelsWritten,
                    pricingDocs);
        }
    }
}
