package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.PodStatus;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Brain-side façade for the cluster-pod registry. Owns the lifecycle
 * of <em>this</em> pod's row in {@code brain_pods} — register on
 * ready, heartbeat on a fixed schedule, set STOPPED on shutdown — and
 * exposes a small read API for callers that want to look at the
 * cluster (admin tools, future routing).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Spring wires the service. The {@link #podId} (UUID) and
 *       {@link #nodeName} are picked here so they are stable from
 *       this moment on, even if registration retries.</li>
 *   <li>{@link ApplicationReadyEvent} fires →
 *       {@link #onApplicationReady} writes the row with status
 *       {@link PodStatus#STARTING} and immediately upserts to
 *       {@link PodStatus#RUNNING}. Two writes are deliberate: a
 *       Mongo-side observer that scrapes "STARTING" rows can see the
 *       intent before the pod claims work.</li>
 *   <li>{@link #heartbeat} runs every
 *       {@link ClusterProperties#getHeartbeatInterval()} and refreshes
 *       {@code lastHeartbeatAt} + {@code activeProjects}.</li>
 *   <li>{@link #onShutdown} sets status {@link PodStatus#STOPPED}.
 *       Best-effort — a crash leaves the row at RUNNING and observers
 *       use {@link BrainPodService#isStale} to detect that.</li>
 * </ol>
 */
@Service
@EnableConfigurationProperties(ClusterProperties.class)
@RequiredArgsConstructor
@Slf4j
public class ClusterService {

    private final BrainPodService brainPodService;
    private final ProjectService projectService;
    private final LocationService locationService;
    private final ClusterNodeNameGenerator nameGenerator;
    private final ClusterProperties properties;

    @Value("${vance.build.version:dev}")
    private String buildVersion;

    /** Stable for the life of this Spring context. */
    private final String podId = UUID.randomUUID().toString();

    /** Picked once on first registration attempt; stays stable across retries. */
    private volatile String nodeName = "";

    /** {@code true} once {@link #onApplicationReady} successfully wrote our row. */
    private volatile boolean registered = false;

    /**
     * Spring's {@link ApplicationReadyEvent} fires after every
     * {@code @PostConstruct} has completed and the web server is
     * listening — the right moment to declare ourselves alive. A
     * registration failure here logs but does not crash the boot;
     * the next heartbeat tick will retry.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (registered) return;
        Instant now = Instant.now();
        BrainPodDocument doc = BrainPodDocument.builder()
                .clusterId(properties.getId())
                .podId(podId)
                .nodeName(resolveNodeName())
                .endpoint(locationService.getPodAddress())
                .status(PodStatus.STARTING)
                .bootedAt(now)
                .lastHeartbeatAt(now)
                .activeProjects(snapshotActiveProjects())
                .resourcesStartupScore(properties.getResources().getStartupScore())
                .resourcesMaxScore(properties.getResources().getMaxScore())
                .resourcesCurrentScore(snapshotCurrentScore())
                .version(buildVersion)
                .build();
        try {
            registerWithRetry(doc);
            // Two-phase write: STARTING is now persisted; flip to RUNNING.
            brainPodService.heartbeat(podId, Instant.now(), PodStatus.RUNNING,
                    snapshotActiveProjects(),
                    snapshotCurrentScore(),
                    properties.getResources().getStartupScore(),
                    properties.getResources().getMaxScore());
            registered = true;
            log.info("ClusterService registered: cluster='{}' nodeName='{}' podId='{}' endpoint='{}'",
                    properties.getId(), nodeName, podId, doc.getEndpoint());
        } catch (RuntimeException e) {
            log.error("ClusterService registration failed; will retry on next heartbeat: {}",
                    e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${vance.cluster.heartbeat-interval:PT1M}")
    public void heartbeat() {
        if (!registered) {
            // Boot didn't fire ready yet, or registration failed — let
            // the ready handler / a later tick retry.
            return;
        }
        try {
            brainPodService.heartbeat(podId, Instant.now(), PodStatus.RUNNING,
                    snapshotActiveProjects(),
                    snapshotCurrentScore(),
                    properties.getResources().getStartupScore(),
                    properties.getResources().getMaxScore());
        } catch (IllegalStateException e) {
            // Row vanished — admin purged us. Re-create.
            log.warn("ClusterService heartbeat: pod row missing, re-registering");
            registered = false;
            onApplicationReady();
        } catch (RuntimeException e) {
            log.warn("ClusterService heartbeat failed: {}", e.toString());
        }
    }

    @PreDestroy
    void onShutdown() {
        if (!registered) return;
        try {
            brainPodService.setStatus(podId, PodStatus.STOPPED, Instant.now());
            log.info("ClusterService shutdown: marked pod '{}' as STOPPED", nodeName);
        } catch (RuntimeException e) {
            log.warn("ClusterService shutdown write failed: {}", e.toString());
        }
    }

    // ─── public read API ────────────────────────────────────────────

    /** Live list of every pod row in this pod's cluster (any status). */
    public List<BrainPodDocument> listCluster() {
        return brainPodService.listCluster(properties.getId());
    }

    /**
     * Resolves a node-name (or raw {@code host:port}) to its registered
     * endpoint, <em>without</em> a liveness check. Returns empty only if
     * the name is unknown. Use this for admin/display and self-identity
     * comparisons — for cross-pod <em>routing</em> use
     * {@link #resolveLiveEndpoint} so a dead pod's stale endpoint is never
     * dialled.
     */
    public Optional<String> resolveEndpoint(String nodeNameOrEndpoint) {
        return brainPodService.resolveEndpoint(properties.getId(), nodeNameOrEndpoint);
    }

    /**
     * Routing-grade resolve: returns the endpoint only when the target
     * node is backed by a live (non-stale, non-stopped) pod. Empty means
     * "no live owner" — the caller should adopt the project locally,
     * rebuild its cache on the next claim, or surface a {@code 409}.
     * This is the primitive every cross-pod router must call. See
     * {@link de.mhus.vance.shared.cluster.BrainPodService#resolveLiveEndpoint}.
     */
    public Optional<String> resolveLiveEndpoint(String nodeNameOrEndpoint) {
        return brainPodService.resolveLiveEndpoint(
                properties.getId(), nodeNameOrEndpoint, properties.getStaleAfter());
    }

    /**
     * Snapshot of every node-name in this cluster that is not stale and
     * not stopped — the CAS predicate in {@code ProjectService.claim()}
     * and the startup-cleanup sweep both use this to decide which
     * {@code homeNode} values are still backed by a live pod.
     *
     * <p>Always includes {@link #selfNodeName()} once registration
     * completed — a fresh pod must not race itself out of its own claim.
     */
    public Set<String> liveClusterNodeNames() {
        return brainPodService.listLiveClusterNodeNames(
                properties.getId(), properties.getStaleAfter());
    }

    /**
     * Live (non-stale, non-stopped) pods in this cluster, sorted by
     * ascending load — {@code resourcesCurrentScore / resourcesMaxScore}.
     * Used by the Cluster-Master Distributor to pick the next best target
     * for an orphaned project.
     */
    public List<BrainPodDocument> liveClusterPods() {
        Set<String> liveNames = liveClusterNodeNames();
        return brainPodService.listCluster(properties.getId()).stream()
                .filter(p -> p.getNodeName() != null && liveNames.contains(p.getNodeName()))
                .sorted((a, b) -> {
                    double aLoad = loadFraction(a);
                    double bLoad = loadFraction(b);
                    return Double.compare(aLoad, bLoad);
                })
                .toList();
    }

    private static double loadFraction(BrainPodDocument pod) {
        int max = Math.max(1, pod.getResourcesMaxScore());
        return ((double) pod.getResourcesCurrentScore()) / max;
    }

    /** This pod's own row, or empty if registration hasn't happened yet. */
    public Optional<BrainPodDocument> selfPod() {
        return brainPodService.findByPodId(podId);
    }

    public String selfPodId() { return podId; }

    public String selfNodeName() { return nodeName; }

    public String selfClusterId() { return properties.getId(); }

    public boolean isStale(BrainPodDocument doc, Instant now) {
        return brainPodService.isStale(doc, now, properties.getStaleAfter());
    }

    // ─── internals ──────────────────────────────────────────────────

    private String resolveNodeName() {
        if (nodeName.isEmpty()) {
            String configured = properties.getNodeName();
            nodeName = (configured != null && !configured.isBlank())
                    ? configured.trim()
                    : nameGenerator.generate();
        }
        return nodeName;
    }

    private void registerWithRetry(BrainPodDocument doc) {
        int retries = Math.max(1, properties.getRegistrationMaxRetries());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                brainPodService.register(doc);
                return;
            } catch (BrainPodService.NodeNameTakenException e) {
                last = e;
                if (properties.getNodeName() != null && !properties.getNodeName().isBlank()) {
                    // Explicit config: do not silently rename — the operator wanted this name.
                    throw e;
                }
                String fresh = nameGenerator.generate();
                log.warn("ClusterService: nodeName '{}' taken — retrying as '{}' (attempt {}/{})",
                        nodeName, fresh, attempt, retries);
                nodeName = fresh;
                doc.setNodeName(fresh);
            }
        }
        throw new IllegalStateException(
                "ClusterService could not pick a free node-name after "
                        + retries + " attempts", last);
    }

    /**
     * Builds the denormalised {@code "<tenantId>/<projectName>"} list
     * for {@code activeProjects}. Read directly off
     * {@code ProjectService.findByHomeNode} (any status) so a
     * heartbeat always reflects the truth at tick time.
     */
    private List<String> snapshotActiveProjects() {
        String node = resolveNodeName();
        if (node.isBlank()) return List.of();
        List<ProjectDocument> mine = projectService.findByHomeNode(node);
        return mine.stream()
                .map(p -> p.getTenantId() + "/" + p.getName())
                .sorted()
                .toList();
    }

    /**
     * Sum of {@code homeResourceScore} over every non-CLOSED project
     * currently owned by this pod's node-name. Derived per beat so the
     * Distributor sees the load each pod actually carries — no separate
     * update path on bring/suspend needed.
     */
    private int snapshotCurrentScore() {
        String node = resolveNodeName();
        if (node.isBlank()) return 0;
        return projectService.sumScoreByHomeNode(node);
    }
}
