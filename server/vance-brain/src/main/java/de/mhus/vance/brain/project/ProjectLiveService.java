package de.mhus.vance.brain.project;

import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.api.projects.RunnerPhase;
import de.mhus.vance.api.projects.RunnerStatusDto;
import de.mhus.vance.api.ws.SignalFrame;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.ws.signals.SignalBroadcaster;
import de.mhus.vance.shared.livestate.LiveStateStore;
import de.mhus.vance.shared.livestate.LiveStateStore.ProjectEntry;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectActualState;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectOwnership;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import de.mhus.vance.shared.runner.RunnerBindingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * What the user sees of a project and its runner: the live state in Redis, and the push of its
 * changes over the {@code signals} channel on the path {@value #PATH}.
 *
 * <p>Information only. This pod writes the entry of a project when it claims it, changes what it
 * does with it, or lets it go, and renews it with the lease; the runner entries come from whoever
 * manages the runners and reach the clients through {@link LiveStateStore#RUNNER_CHANNEL}. An
 * entry that is missing or has run out reads as {@link ProjectLiveState#UNKNOWN}. Nothing in the
 * lifecycle depends on this, so a failure here is logged and goes no further.
 *
 * <p>Project frames reach only subscribers who may read the project.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectLiveService {

    /** Signal path of the status feed; one subscription per client carries every project. */
    public static final String PATH = "_status";

    public static final String SIGNAL_PROJECT = "project-state";
    public static final String SIGNAL_RUNNER = "runner-state";

    /** How long a stopped project stays on record before it reads as unknown again. */
    private static final Duration STOPPED_TTL = Duration.ofHours(1);

    private final LiveStateStore store;
    private final SignalBroadcaster signals;
    private final ClusterService clusterService;
    private final VanceRedisMessagingService redis;
    private final RunnerBindingService runnerBindings;
    private final PermissionService permissionService;
    private final SecurityContextFactory securityContexts;

    @PostConstruct
    void start() {
        signals.restrictAudience(PATH, (connection, frame) -> {
            if (!SIGNAL_PROJECT.equals(frame.getSignal()) || frame.getData() == null) {
                return true;
            }
            Object project = frame.getData().get("project");
            return project instanceof String name
                    && permissionService.check(
                            securityContexts.fromConnection(connection),
                            new Resource.Project(connection.getTenantId(), name),
                            Action.READ);
        });
        redis.subscribeAcrossTenants(LiveStateStore.RUNNER_CHANNEL, (topic, body) -> {
            String tenantId = VanceRedisMessagingService.tenantFromTopic(topic);
            store.decodeRunner(body).ifPresent(runner -> signals.broadcastLocal(tenantId, runnerFrame(runner)));
        });
    }

    @PreDestroy
    void stop() {
        redis.unsubscribeAcrossTenants(LiveStateStore.RUNNER_CHANNEL);
    }

    // ─── writing ────────────────────────────────────────────────────────

    /** This pod now does {@code state} with the project: record it and tell the subscribers. */
    public void record(String tenantId, String project, ProjectLiveState state) {
        try {
            Duration ttl = state == ProjectLiveState.STOPPED ? STOPPED_TTL : clusterService.leaseTtl();
            publish(tenantId, project, store.putProject(tenantId, project, state, ttl));
        } catch (RuntimeException e) {
            log.debug("live state of '{}/{}' not recorded: {}", tenantId, project, e.toString());
        }
    }

    /** Same as {@link #record} for the stored actual state of the project document. */
    public void recordActual(String tenantId, String project, @Nullable ProjectActualState actual) {
        record(tenantId, project, liveOf(actual));
    }

    /**
     * Extends the life of the project's entry along with its lease.
     *
     * @return {@code false} when there was no entry to extend — run out after a missed tick, or
     *     lost with the store — so the caller can write it again from the project document
     */
    public boolean renew(String tenantId, String project) {
        try {
            Optional<ProjectEntry> renewed = store.renewProject(tenantId, project, clusterService.leaseTtl());
            renewed.ifPresent(entry -> publish(tenantId, project, entry));
            return renewed.isPresent();
        } catch (RuntimeException e) {
            log.debug("live state of '{}/{}' not renewed: {}", tenantId, project, e.toString());
            return true;
        }
    }

    private void publish(String tenantId, String project, ProjectEntry entry) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project", project);
        data.put("liveState", entry.state().name());
        data.put("expiresAtMs", expiryShown(entry));
        signals.broadcast(
                tenantId,
                SignalFrame.builder()
                        .path(PATH)
                        .signal(SIGNAL_PROJECT)
                        .data(data)
                        .build());
    }

    private static SignalFrame runnerFrame(RunnerStatusDto runner) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runnerId", runner.getRunnerId());
        data.put("phase", runner.getPhase().name());
        data.put("restarts", runner.getRestarts());
        data.put("lastSeenAtMs", runner.getLastSeenAtMs());
        data.put("observedAtMs", runner.getObservedAtMs());
        data.put("expiresAtMs", runner.getExpiresAtMs());
        return SignalFrame.builder().path(PATH).signal(SIGNAL_RUNNER).data(data).build();
    }

    // ─── reading ────────────────────────────────────────────────────────

    /** The project's entry; empty when nothing vouches for it. */
    public Optional<ProjectEntry> entryOf(String tenantId, String project) {
        return store.getProject(tenantId, project);
    }

    /** What a list shows for a project: its state and until when that statement holds ({@code null} = until it changes). */
    public record ProjectLiveView(
            ProjectLiveState state, @Nullable Long expiresAtMs) {}

    /**
     * The live state of {@code project} for display. The live entry wins while it exists; without
     * one — never written, run out, or Redis not part of this deployment — the state is derived
     * from the project document, which is the truth anyway: the stored actual state while a live
     * lease backs it and its holder is alive, {@code STOPPED} otherwise. So a project that is not
     * running reads as stopped, not as unknown, however long ago anyone last wrote an entry.
     */
    public ProjectLiveView liveViewOf(String tenantId, ProjectDocument project) {
        Optional<ProjectEntry> entry = entryOf(tenantId, project.getName());
        if (entry.isPresent()) {
            return new ProjectLiveView(entry.get().state(), expiryShown(entry.get()));
        }
        ProjectActualState actual =
                ProjectOwnership.effectiveActualState(project, Instant.now(), clusterService.leaseTtl());
        String holder = project.getHomePodId();
        if (actual != ProjectActualState.STOPPED && holder != null && clusterService.isHolderGone(holder)) {
            actual = ProjectActualState.STOPPED;
        }
        return new ProjectLiveView(liveOf(actual), null);
    }

    /**
     * A stopped project stays stopped until somebody starts it, which comes with a new entry and a
     * frame — so a stopped state never runs out on the client. The TTL on the entry only bounds how
     * long the store keeps it.
     */
    private static @Nullable Long expiryShown(ProjectEntry entry) {
        return entry.state() == ProjectLiveState.STOPPED ? null : entry.expiresAtMs();
    }

    /** The runner serving the project (its own, else the tenant's), when one is bound. */
    public Optional<RunnerStatusDto> runnerOf(String tenantId, String project) {
        return runnerBindings.resolve(tenantId, project).map(effective -> {
            String runnerId = effective.binding().getRunnerId();
            return store.getRunner(tenantId, runnerId)
                    .orElseGet(() -> RunnerStatusDto.builder()
                            .runnerId(runnerId)
                            .phase(RunnerPhase.UNKNOWN)
                            .build());
        });
    }

    static ProjectLiveState liveOf(@Nullable ProjectActualState actual) {
        if (actual == null) {
            return ProjectLiveState.UNKNOWN;
        }
        return switch (actual) {
            case STOPPED -> ProjectLiveState.STOPPED;
            case STARTING -> ProjectLiveState.STARTING;
            case RUNNING -> ProjectLiveState.RUNNING;
            case STOPPING -> ProjectLiveState.STOPPING;
        };
    }
}
