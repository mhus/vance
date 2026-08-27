package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.cluster.BrainPodCapacity;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.PodSelector;
import de.mhus.vance.shared.cluster.PodStatus;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
    private final ClusterTimeWindows timeWindows;
    /**
     * Publishing rather than calling {@code PlacementAccelerator} directly:
     * the distributor already depends on this service, so the reverse edge
     * would be a constructor cycle.
     */
    private final ApplicationEventPublisher eventPublisher;

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
     *
     * <p>{@link Ordered#HIGHEST_PRECEDENCE} so this pod's registry row normally
     * exists before other boot listeners look for it. <b>Nothing depends on
     * that ordering</b>, and deliberately so: a dependency between two
     * {@code ApplicationReadyEvent} listeners expressed as a global sort is
     * broken by the next listener somebody adds, silently. Callers with a real
     * precondition state it themselves — see {@link #ensureRegistered()} and
     * {@code ProjectStartupReclaimer}.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onApplicationReady() {
        ensureRegistered();
    }

    /**
     * Registers this pod if it is not registered yet. Idempotent and safe to
     * call from any boot listener that needs the row to exist — that is the
     * mechanism the ordering above is <em>not</em>.
     */
    public void ensureRegistered() {
        if (registered) return;
        Instant now = Instant.now();
        // Fail the boot on a malformed label rather than register a pod whose
        // labels no selector can name. The seed is the one moment where this is
        // catchable — the heartbeat never touches these fields again.
        PodSelector.validate(properties.getLabels());
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
                // Seed only — see BrainPodDocument.labels. Every later change
                // arrives from outside the process and must not be undone by
                // the next beat.
                .labels(new java.util.LinkedHashMap<>(properties.getLabels()))
                .exclusive(properties.isExclusive())
                .version(buildVersion)
                .build();
        try {
            registerWithRetry(doc);
            // Two-phase write: STARTING is now persisted; flip to RUNNING.
            brainPodService.heartbeat(podId, Instant.now(), PodStatus.RUNNING,
                    doc.getEndpoint(),
                    snapshotActiveProjects(),
                    snapshotCurrentScore(),
                    properties.getResources().getStartupScore(),
                    properties.getResources().getMaxScore());
            registered = true;
            log.info("ClusterService registered: cluster='{}' nodeName='{}' podId='{}' endpoint='{}'",
                    properties.getId(), nodeName, podId, doc.getEndpoint());
            // A candidate appeared. Published after `registered = true` and
            // after the row is RUNNING, because the round this triggers reads
            // the registry — announcing a pod that is not yet selectable would
            // spend the round and change nothing.
            eventPublisher.publishEvent(new PlacementInputChangedEvent(
                    "pod registered: " + nodeName));
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
            // Re-validate our advertised address first: if the host's network
            // changed under a live process, republish the current endpoint so
            // routing (and our own workspace self-proxy) stops hitting a dead
            // address. No-op when the address is still valid.
            brainPodService.heartbeat(podId, Instant.now(), PodStatus.RUNNING,
                    locationService.refreshPodAddress(),
                    snapshotActiveProjects(),
                    snapshotCurrentScore(),
                    properties.getResources().getStartupScore(),
                    properties.getResources().getMaxScore());
        } catch (IllegalStateException e) {
            // Row vanished — admin purged us. Re-create.
            log.warn("ClusterService heartbeat: pod row missing, re-registering");
            registered = false;
            ensureRegistered();
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
     * {@link #resolveEndpointByPodId} so a dead pod's stale endpoint is never
     * dialled.
     */
    public Optional<String> resolveEndpoint(String nodeNameOrEndpoint) {
        return brainPodService.resolveEndpoint(properties.getId(), nodeNameOrEndpoint);
    }

    /**
     * Resolves the endpoint of a pod by its {@code podId} — the routing
     * primitive for cross-pod hops.
     *
     * <p><b>The liveness gate is here on purpose.</b> The caller arrives with a
     * pod id taken from a valid ownership lease, and it is tempting to conclude
     * that the holder must therefore be alive. It does not follow: ownership
     * and liveness are two questions with two clocks
     * ({@link ClusterTimeWindows}), and the lease TTL is the longer of the two
     * by default. A {@code kill -9}'d holder keeps a valid lease for up to
     * {@code leaseTtl} while its {@code brain_pods} row sits at
     * {@code RUNNING} with a dead {@code host:port} — every hop routed there
     * turns into a connect timeout. So the lease answers "whose is it" and this
     * answers "does that still mean anything", and routing needs both.
     *
     * <p>Empty means the row is gone (admin purge, cleanup sweep), stopped, or
     * stale. Callers treat all of that like "no owner": adopt locally, or
     * surface a {@code 409}.
     */
    public Optional<String> resolveEndpointByPodId(String podId) {
        Instant now = Instant.now();
        return brainPodService.findByPodId(podId)
                .filter(pod -> pod.getStatus() != PodStatus.STOPPED)
                .filter(pod -> !brainPodService.isStale(pod, now, timeWindows.podLiveness()))
                .map(BrainPodDocument::getEndpoint)
                .filter(endpoint -> !endpoint.isBlank());
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
                properties.getId(), timeWindows.podLiveness());
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
                .sorted((a, b) -> Double.compare(
                        BrainPodCapacity.loadFraction(a), BrainPodCapacity.loadFraction(b)))
                .toList();
    }



    /** This pod's own row, or empty if registration hasn't happened yet. */
    public Optional<BrainPodDocument> selfPod() {
        return brainPodService.findByPodId(podId);
    }

    public String selfPodId() { return podId; }

    /**
     * Whether this pod's row exists in {@code brain_pods} yet.
     *
     * <p>For boot-time callers whose limits are read from that row and default
     * to permissive when it is absent — {@code selfPod()} answering empty is
     * "I cannot see myself", which several paths deliberately read as "not
     * blocked". Opportunistic work has to be able to tell that apart from
     * "checked, and allowed".
     */
    public boolean isRegistered() { return registered; }

    /**
     * This pod's node name. Resolved on first use rather than returned raw so
     * callers that log or denormalise it (the lease writes it for display) get
     * a name even before registration completed.
     */
    public String selfNodeName() { return resolveNodeName(); }

    public String selfClusterId() { return properties.getId(); }

    /**
     * This pod's {@code ip:port} — the address it advertises to the cluster.
     *
     * <p>Straight from {@link de.mhus.vance.shared.location.LocationService}
     * rather than from this pod's registry row, so it answers before
     * registration has happened and cannot be stale. Same source the
     * registration itself uses.
     */
    public String selfEndpoint() { return locationService.getPodAddress(); }

    /**
     * How long a project ownership lease stays valid without renewal — the
     * single place brain code reads this from, so the claim path, the renewal
     * tick and every {@code ProjectOwnership} caller cannot drift apart.
     */
    public Duration leaseTtl() { return timeWindows.leaseTtl(); }

    /**
     * How long a derived routing answer may be cached before it has to be
     * re-derived. See {@link ClusterTimeWindows#routingAnswerMaxAge()} — it is
     * the shorter of the two gates {@link #resolveEndpointByPodId} applies, so
     * a cache in front of that call cannot outlive either of them.
     */
    public Duration routingAnswerMaxAge() { return timeWindows.routingAnswerMaxAge(); }

    public boolean isStale(BrainPodDocument doc, Instant now) {
        return brainPodService.isStale(doc, now, timeWindows.podLiveness());
    }

    // ─── internals ──────────────────────────────────────────────────

    /**
     * Picks the node name once, on whichever thread asks first.
     *
     * <p>{@code synchronized} because the unconfigured branch generates a
     * <em>random</em> name: check-then-act on a volatile field lets two threads
     * both see the empty field, generate two different names, and hand one of
     * them out to a caller that will never see it in the registry. The claim
     * path denormalises this name onto the lease, so the callers are no longer
     * just the boot thread.
     */
    private synchronized String resolveNodeName() {
        if (nodeName.isEmpty()) {
            String configured = properties.getNodeName();
            nodeName = (configured != null && !configured.isBlank())
                    ? configured.trim()
                    : nameGenerator.generate();
        }
        return nodeName;
    }

    /** Re-roll after a name collision. Same lock as {@link #resolveNodeName}. */
    private synchronized void replaceNodeName(String fresh) {
        nodeName = fresh;
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
                replaceNodeName(fresh);
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
     * {@code ProjectService.findByHomePodId} (any status) so a
     * heartbeat always reflects the truth at tick time.
     *
     * <p>Keyed on {@code podId}, not on the node name: the name is
     * operator-configurable, so a restarted pod with a pinned name would
     * otherwise report its dead predecessor's projects as its own.
     */
    private List<String> snapshotActiveProjects() {
        List<ProjectDocument> mine = projectService.findByHomePodId(podId);
        return mine.stream()
                .map(p -> p.getTenantId() + "/" + p.getName())
                .sorted()
                .toList();
    }

    /**
     * Sum of {@code homeResourceScore} over every non-CLOSED project this pod
     * holds a lease on. Derived per beat so the Distributor sees the load each
     * pod actually carries — no separate update path on bring/suspend needed.
     */
    private int snapshotCurrentScore() {
        return projectService.sumScoreByHomePodId(podId);
    }
}
