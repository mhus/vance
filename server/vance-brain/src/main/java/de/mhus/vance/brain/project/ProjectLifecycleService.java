package de.mhus.vance.brain.project;

import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.ClusterFullException;
import de.mhus.vance.brain.cluster.placement.PlacementTrigger;
import de.mhus.vance.brain.cluster.placement.ProjectPlacement;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.rag.ProjectRagService;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.exec.EphemeralHandler;
import de.mhus.vance.exec.TempHandler;
import de.mhus.vance.shared.project.ProjectActualState;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectOwnership;
import de.mhus.vance.shared.project.ProjectQuotaService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Orchestrator for the project lifecycle (see {@code
 * specification/workspace-management.md} §11). Wraps three steps:
 *
 * <ul>
 *   <li>{@link #bring} — claim pod, initialise the workspace folder, mark
 *       RUNNING (and request engines start).</li>
 *   <li>{@link #suspend} — request engines stop, mark SUSPENDED. The
 *       workspace folder stays where it is.</li>
 *   <li>{@link #idle} — the same teardown, desired state IDLE: the system
 *       stopped the project and any access wakes it again.</li>
 *   <li>{@link #close} — mark CLOSED, move to the archived group, stop
 *       engines on the home pod, dispose workspace.</li>
 *   <li>{@link #reopen} — CLOSED back to INIT in the group it was archived
 *       from, then {@link #bring} with a fresh workspace.</li>
 * </ul>
 *
 * <p>{@link #create} and {@link #reopen} are where a project becomes active,
 * so both check the tenant's active-project quota
 * ({@link ProjectQuotaService}). The admin shell bypasses this service and
 * checks the quota itself, with {@code --ignore-quota} as the way past it.
 *
 * <p>Engine start/stop is signalled via Spring events
 * ({@link ProjectEnginesStartRequested} / {@link ProjectEnginesStopRequested}).
 * The stop listeners unload what a project keeps loaded on its pod —
 * scheduler entries, hooks, server tools, caches. They do not halt a think
 * process mid-turn; that is the session lifecycle's stop.
 *
 * <p><b>Desired and actual state.</b> {@code ProjectDocument.status} is what
 * somebody wants ({@link ProjectStatus}); {@code actualState} is what the lease
 * holder is doing ({@link ProjectActualState}) and only means something while
 * that lease lives. The desired state is written first and never by a
 * shutdown; the actual state follows as the pod brings the project up or takes
 * it down.
 *
 * <p>Crash recovery: every transition is idempotent. {@link #bring} re-runs
 * from any non-CLOSED desired state; a crash mid-start or mid-stop leaves a
 * stored actual state that no live lease backs, which reads as STOPPED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectLifecycleService {

    private final ProjectService projectService;
    private final ProjectManagerService projectManager;
    private final ProjectPlacement placementService;
    private final WorkspaceService workspaceService;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * Pod-local "what have I started" — the only honest source for the
     * {@link #bring} short-circuit. See the class comment there.
     */
    private final ProjectActivationRegistry activationRegistry;
    /**
     * Lazy provider for the project-RAG service — keeps the lifecycle
     * decoupled from RAG bean wiring (embedding-provider settings might
     * not be configured for every deployment) and lets bring/close
     * tolerate a missing or misconfigured RAG without breaking project
     * activation.
     */
    private final ObjectProvider<ProjectRagService> projectRagProvider;
    /**
     * {@link ObjectProvider} so we don't close the bean cycle:
     * {@code SessionChatBootstrapper} → {@code SessionCreateHandler} →
     * already touches a number of services; lazy lookup here keeps this
     * service constructable without forcing eager wiring.
     */
    private final ObjectProvider<SessionChatBootstrapper> chatBootstrapperProvider;
    /**
     * Same lazy-lookup reasoning — the router pulls in
     * {@code EngineWsClient} which has its own connection state we don't
     * need to instantiate just because lifecycle is being touched.
     */
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;
    /**
     * Seeds the creator of a project as its project ADMIN — project
     * memberships live in Vance under every permission provider.
     */
    private final de.mhus.vance.shared.role.RoleBootstrap roleBootstrap;

    private final ProjectQuotaService quotaService;
    private final ProjectGroupService projectGroupService;
    private final ClusterService clusterService;
    private final ProjectLiveService projectLive;
    /** Reaches the home pod when an archive lands elsewhere — see {@link #stopWhereRunning}. */
    private final ClusterBringClient bringClient;
    /**
     * Lazy — {@code SessionLifecycleService} reaches {@code ThinkEngineService},
     * whose tool graph leads back here. Used by the archive to interrupt and
     * suspend the project's sessions.
     */
    private final ObjectProvider<SessionLifecycleService> sessionLifecycleProvider;

    /**
     * One lock per project key, serialising {@link #bring} on this pod. The
     * lease keeps other pods out; on this pod self-pull, the distributor and
     * the WS handlers all call {@code bring} and may hit the same project at
     * once. Without the lock both threads pass the "already active here"
     * check and start the engines twice. With it the second caller waits and
     * then takes the short-circuit. Bounded by the projects this pod has
     * brought.
     */
    private final ConcurrentHashMap<String, ReentrantLock> bringLocks = new ConcurrentHashMap<>();

    /**
     * Create a new project and bring it to RUNNING in one shot —
     * the workflow that {@link de.mhus.vance.brain.tools.eddie.ProjectCreateTool}
     * (Eddie) and the project-create REST endpoint (Web-UI) share.
     *
     * <p>Returns the {@link ProjectDocument} after both steps finished:
     * inserted into Mongo, claimed by this pod, workspace initialised,
     * status RUNNING.
     *
     * <p>Session and chat-process are <em>not</em> created here — that's
     * {@link #bootstrapChat(BootstrapChatRequest)}, called separately
     * when the caller actually wants a worker to talk to (Eddie does;
     * a Web-UI "new project" button might not).
     *
     * @throws ProjectService.ProjectAlreadyExistsException
     *     when {@code name} already lives in this tenant.
     * @throws ProjectService.ReservedProjectNameException
     *     when {@code name} starts with the reserved system prefix and
     *     {@code kind != SYSTEM}.
     * @throws ProjectQuotaService.ProjectQuotaExceededException
     *     when a non-SYSTEM project would exceed the tenant's active-project
     *     quota.
     */
    public ProjectDocument create(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable String projectGroupId,
            @Nullable List<String> teamIds,
            ProjectKind kind,
            @Nullable String createdBy) {
        if (kind != ProjectKind.SYSTEM) {
            quotaService.checkOneMore(tenantId);
        }
        ProjectDocument created = projectService.create(tenantId, name, title, projectGroupId, teamIds, kind);
        // Where it runs is the placement service's call — local-first when this
        // pod has room, otherwise the least-loaded pod that does. The homeless
        // tenant project is never placed — the placement service skips it.
        try {
            placementService.place(created, PlacementTrigger.CREATE);
        } catch (ClusterFullException e) {
            // "Accepted, waiting for a pod" is a legitimate outcome once
            // selectors exist, and provisioning one takes minutes — so nobody
            // can wait for it here. The project stays created and marked as
            // pending (the placement decision did that); the caller reports the
            // state instead of an error, or a correctly refused selector reads
            // as a broken create
            // (planning/project-placement-labels.md §7).
            log.info(
                    "Project '{}/{}' created but not placed ({}) — waiting for a matching pod",
                    tenantId,
                    name,
                    e.getGap());
        }
        // Re-read: the bring behind place() moved the lease and the status.
        ProjectDocument saved = projectService
                .findByTenantAndName(tenantId, name)
                .orElseThrow(() ->
                        new ProjectService.ProjectNotFoundException("Project '" + name + "' vanished during create"));
        // Seed the creator as project ADMIN so they can manage the project
        // they just made.
        if (createdBy != null && !createdBy.isBlank() && kind != ProjectKind.SYSTEM) {
            roleBootstrap.grantProjectAdmin(tenantId, saved.getName(), createdBy);
        }
        return saved;
    }

    /**
     * Create a session inside an existing project and spawn its standard
     * chat-process — the second half of what
     * {@link de.mhus.vance.brain.tools.eddie.ProjectCreateTool} does
     * inline today, also reachable from the Web-UI / Foot once they
     * call this through a lifecycle REST endpoint.
     *
     * <p>If {@link BootstrapChatRequest#initialPrompt()} is non-null,
     * the prompt is pushed at the chat-process via
     * {@link EngineMessageRouter} so it's already queued for the first
     * lane turn. Same dispatch path as
     * {@code project_chat_send} — local-direct or cross-pod-WS depending
     * on the worker's Home Pod.
     */
    public BootstrapResult bootstrapChat(BootstrapChatRequest req) {
        ProjectDocument project = projectService
                .findByTenantAndName(req.tenantId(), req.projectName())
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + req.projectName() + "' not found in tenant '" + req.tenantId() + "'"));

        SessionDocument session = sessionService.create(
                req.tenantId(),
                req.userId(),
                project.getName(),
                req.displayName(),
                req.profile(),
                req.clientVersion(),
                req.clientName());

        ThinkProcessDocument chat = chatBootstrapperProvider
                .getObject()
                .ensureChatProcess(session, req.parentProcessId())
                .orElseThrow(() -> new IllegalStateException(
                        "Chat-process bootstrap failed for session '" + session.getSessionId() + "'"));

        if (req.initialPrompt() != null && !req.initialPrompt().isBlank()) {
            PendingMessageDocument msg = PendingMessageDocument.builder()
                    .type(PendingMessageType.USER_CHAT_INPUT)
                    .at(Instant.now())
                    .fromUser(req.senderProcessId() == null ? req.userId() : "process:" + req.senderProcessId())
                    .content(req.initialPrompt())
                    .build();
            boolean ok = messageRouterProvider.getObject().dispatch(req.senderProcessId(), chat.getId(), msg);
            if (!ok) {
                log.warn("bootstrapChat: initialPrompt dispatch failed for chat='{}'", chat.getId());
            }
        }

        log.info(
                "bootstrapChat: tenant='{}' project='{}' session='{}' chat='{}' parent='{}' withPrompt={}",
                req.tenantId(),
                project.getName(),
                session.getSessionId(),
                chat.getId(),
                req.parentProcessId(),
                req.initialPrompt() != null);
        return new BootstrapResult(project, session, chat);
    }

    /** Parameter object for {@link #bootstrapChat(BootstrapChatRequest)}. */
    public record BootstrapChatRequest(
            String tenantId,
            String projectName,
            String userId,
            String displayName,
            String profile,
            String clientVersion,
            @Nullable String clientName,
            /**
             * If set, is recorded as the chat-process's {@code parentProcessId}
             * — Eddie passes her own process id so worker
             * {@code ProcessEvent}s route back to her via
             * {@code ParentNotificationListener}. Web-UI / Foot leave it null.
             */
            @Nullable String parentProcessId,
            /** Optional first user-chat-input pushed at the worker. */
            @Nullable String initialPrompt,
            /**
             * Sender id used on the {@code initialPrompt} dispatch; only
             * looked at when {@link #initialPrompt} is set. Eddie passes
             * her process id so the EngineMessage carries proper sender
             * provenance; otherwise pass null.
             */
            @Nullable String senderProcessId) {}

    /** Triple of artefacts {@link #bootstrapChat(BootstrapChatRequest)} produces. */
    public record BootstrapResult(ProjectDocument project, SessionDocument session, ThinkProcessDocument chatProcess) {}

    /**
     * Move a project onto this pod: take the lease, set the desired state to
     * RUNNING and the actual state to STARTING, initialise the workspace (an
     * existing folder is kept as it is), publish
     * {@link ProjectEnginesStartRequested}, record the actual state as RUNNING.
     * An explicit bring wakes a project from any non-CLOSED desired state,
     * IDLE and SUSPENDED alike.
     *
     * <p><b>Idempotence keys on this pod's activation registry, not on
     * {@code status}.</b> The short-circuit needs "already running
     * <em>here</em>", and {@code status} cannot answer that — it is the
     * shared, desired state, and after a crash it still says {@code RUNNING}
     * because the only thing that ever writes it back is an explicit
     * {@link #suspend} or {@link #idle}. Reading
     * it as "nothing to do" meant the new lease holder owned the project
     * without ever starting anything for it: no workspace, no session unbind,
     * and no {@link ProjectEnginesStartRequested}, so scheduler, hooks, tool
     * preload and kit provisioning stayed dark until somebody noticed and
     * added a private trigger. That is the defect this whole track is about
     * ({@code planning/project-ownership-lease-design.md} §1.2).
     *
     * <p>Consequence, intended: a {@code RUNNING} project whose lease expired
     * is the <em>normal</em> recovery case and runs the full pass.
     *
     * <p>The homeless tenant project (see {@link ProjectService#isHomeless})
     * is refused: nothing runs there — no workspace, no engines, no
     * scheduler. Its documents are the cascade layer and are read from
     * Mongo by whichever project needs them.
     *
     * <p>Serialised per project on this pod (see {@link #bringLocks}).
     */
    public ProjectDocument bring(String tenantId, String projectName) {
        if (ProjectService.isHomeless(projectName)) {
            throw new ProjectService.SystemProjectProtectedException(
                    "Project '" + projectName + "' is homeless — nothing runs there");
        }
        ReentrantLock lock = bringLocks.computeIfAbsent(
                ProjectActivationRegistry.key(tenantId, projectName), k -> new ReentrantLock());
        lock.lock();
        try {
            return bringLocked(tenantId, projectName);
        } finally {
            lock.unlock();
        }
    }

    private ProjectDocument bringLocked(String tenantId, String projectName) {
        ProjectDocument doc = projectManager.claimForLocalPod(tenantId, projectName);
        if (doc.getStatus() == ProjectStatus.RUNNING && activationRegistry.isActive(tenantId, projectName)) {
            // Already up *on this pod*: lease refreshed, nothing to do.
            log.debug("Project '{}/{}' already active here — lease refreshed", tenantId, projectName);
            return doc;
        }
        // Bring = the project is coming online on *this* pod. No client can be
        // legitimately bound right now — either the previous holder died (and
        // its WS connections died with it), or the project was suspended and
        // it was stopped. Stale boundConnectionId values would
        // otherwise reject the first reconnect with 409 Already-Bound. This
        // covers every path: self-pull, distributor, locator, direct-spawn.
        long unbound = sessionService.unbindAllForProjects(tenantId, List.of(projectName));
        if (unbound > 0) {
            log.info("Project '{}/{}' bring: cleared {} stale session binding(s)", tenantId, projectName, unbound);
        }
        ProjectStatus from = doc.getStatus();
        // The desired state moves first, and as a strict CAS: an explicit
        // suspend or close that landed between the read and this write must win
        // over an implicit bring — "ignore an operator's suspend" is a much
        // worse outcome than "retry the bring on the next tick".
        projectService.transitionStatus(tenantId, projectName, from, ProjectStatus.RUNNING);
        recordActualState(tenantId, projectName, ProjectActualState.STARTING);
        boolean folderExisted = workspaceService.existsForProject(tenantId, projectName);
        try {
            workspaceService.init(tenantId, projectName);
        } catch (RuntimeException e) {
            log.error(
                    "Workspace init failed for '{}/{}' (actual state remains STARTING): {}",
                    tenantId,
                    projectName,
                    e.toString());
            throw e;
        }
        if (!folderExisted
                && from != ProjectStatus.INIT
                && workspaceService.mode().accessible()) {
            // A workspace never moves between pods. A project that was brought
            // before but has no folder here ran on another pod (or lost its
            // disk) — it starts empty, which is correct but should be loud: in
            // a local-workspace install it means the deployment is wrong.
            log.warn(
                    "Project '{}/{}' brought from {} but this pod holds no workspace folder for it — "
                            + "starting with an empty workspace (workspaces do not move between pods)",
                    tenantId,
                    projectName,
                    from);
        }
        ensureProjectRag(tenantId, projectName);
        eventPublisher.publishEvent(new ProjectEnginesStartRequested(tenantId, projectName));
        // Registered after the start event, so a listener that throws leaves
        // the project un-activated and the next bring retries the whole pass
        // rather than short-circuiting on a half-started project.
        activationRegistry.activate(tenantId, projectName);
        doc = finishBring(tenantId, projectName);
        log.info("Project '{}/{}' brought to RUNNING (was {})", tenantId, projectName, from);
        return doc;
    }

    /**
     * Closes {@link #bring}: re-checks that the world still agrees with what
     * this pass did, then records the project as RUNNING.
     *
     * <p>Two things can have changed since the strict CAS at the start. The
     * desired state may no longer be RUNNING — an operator suspended or closed
     * the project while it was starting — and the operator's intent wins over
     * the implicit bring, so this surfaces a conflict. Or the lease may have
     * moved to another pod (it expired during a slow start): that pod runs the
     * project, this bring surfaces the conflict, and the local start it already
     * made is an "active here, lease elsewhere" project that
     * {@link ProjectLeaseService}'s drift reconcile unloads.
     */
    private ProjectDocument finishBring(String tenantId, String projectName) {
        ProjectDocument refreshed = projectService
                .findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '" + tenantId + "'"));
        if (refreshed.getStatus() != ProjectStatus.RUNNING) {
            log.warn(
                    "Project '{}/{}' bring: desired state changed to {} while starting — surfacing conflict",
                    tenantId,
                    projectName,
                    refreshed.getStatus());
            throw new ProjectService.ProjectStatusConflictException(
                    "Project '" + projectName + "' became " + refreshed.getStatus() + " while it was starting");
        }
        if (!projectManager.isOwnedByLocalPod(tenantId, projectName)) {
            log.warn(
                    "Project '{}/{}' bring: the lease moved to another pod — "
                            + "surfacing conflict, the lease reconcile unloads the local start",
                    tenantId,
                    projectName);
            throw new ProjectService.ProjectStatusConflictException(
                    "Project '" + projectName + "' is owned by another pod");
        }
        recordActualState(tenantId, projectName, ProjectActualState.RUNNING);
        return projectService.findByTenantAndName(tenantId, projectName).orElse(refreshed);
    }

    /** Records what this pod is doing with the project; ignored if the lease is no longer ours. */
    private void recordActualState(String tenantId, String projectName, ProjectActualState state) {
        if (projectService.setActualState(tenantId, projectName, clusterService.selfPodId(), state)) {
            projectLive.recordActual(tenantId, projectName, state);
        }
    }

    /**
     * Hand the project over: stop running it here and drop the lease, leaving
     * the <em>intent</em> untouched so the next placement picks it up
     * elsewhere. The drain half of {@code planning/project-placement-labels.md}
     * §8.
     *
     * <p><b>Not {@link #suspend}, and not the involuntary drift path either.</b>
     * Three teardowns exist, and each writes a different amount:
     * <ul>
     *   <li>{@code suspend} changes the intent — the project runs nowhere
     *       afterwards, and {@code findProjectsNeedingOwner} stops selecting
     *       it. Using it to move a project would be an outage, not a move.</li>
     *   <li>{@code ProjectLeaseService}'s drift deactivation writes
     *       <em>nothing</em>: the lease is already gone, so the new owner has
     *       initialised from Mongo and our folder is a stale copy that would
     *       overwrite their state.</li>
     *   <li>This one still holds the lease while it runs, so engines stop
     *       cleanly before the lease goes. The workspace does <em>not</em>
     *       travel: a workspace lives on the disk of the pod that ran the
     *       project and is never moved between pods. A pod that takes the
     *       project over starts with an empty one — persistence beyond that
     *       is the runner's and the platform's concern.</li>
     * </ul>
     *
     * <p>Order is load-bearing: stop engines, deactivate, <em>then</em> release,
     * so no engine still runs here when another pod can claim the project.
     *
     * <p>The homeless tenant project holds no lease and is refused — there is
     * nothing to hand over.
     *
     * @return {@code false} when this pod does not hold the lease, so a caller
     *     can answer 409 rather than pretend something happened
     */
    public boolean release(String tenantId, String projectName) {
        return release(tenantId, projectName, false);
    }

    /**
     * Whether this pod keeps something in the project's workspace that would be lost by moving the
     * project: a RootDir that is more than scratch. A workspace never moves between pods.
     *
     * <p>The bare folder does not count. {@link #bring} creates an empty workspace for every project
     * it starts, also on pods that offer no workspace access at all, and a guard that took that for
     * "has a workspace" would pin every project. Temp and ephemeral RootDirs are scratch by
     * definition (results live as documents); git, python and node RootDirs hold work.
     */
    public boolean holdsWorkspace(String tenantId, String projectName) {
        return workspaceService.listRootDirs(tenantId, projectName).stream()
                .anyMatch(dir -> !SCRATCH_ROOT_DIR_TYPES.contains(dir.getType()));
    }

    private static final Set<String> SCRATCH_ROOT_DIR_TYPES = Set.of(TempHandler.TYPE, EphemeralHandler.TYPE);

    /**
     * {@link #release}, optionally interrupting and suspending the project's
     * open sessions first ({@link SessionLifecycleService#suspendProjectSessions})
     * — the archive's variant, reached through {@code /internal/cluster/release}
     * when the archive lands on another pod. A drain passes {@code false}: the
     * sessions are meant to continue wherever the project goes next.
     */
    public boolean release(String tenantId, String projectName, boolean suspendSessions) {
        if (ProjectService.isHomeless(projectName)) {
            throw new ProjectService.SystemProjectProtectedException(
                    "Project '" + projectName + "' is homeless — it holds no lease to release");
        }
        if (!activationRegistry.isActive(tenantId, projectName)
                && !projectManager.isOwnedByLocalPod(tenantId, projectName)) {
            log.debug("Project '{}/{}' release: not held here", tenantId, projectName);
            return false;
        }
        if (suspendSessions) {
            suspendSessionsHere(tenantId, projectName);
        }
        recordActualState(tenantId, projectName, ProjectActualState.STOPPING);
        eventPublisher.publishEvent(new ProjectEnginesStopRequested(tenantId, projectName));
        activationRegistry.deactivate(tenantId, projectName);
        if (isClosed(tenantId, projectName)) {
            // An archive is releasing: the archiving pod disposes the workspace,
            // possibly before this release finishes (a timed-out request counts
            // as "unreachable" there). A CLOSED project's workspace ends here
            // too, so nothing is left behind on this pod.
            workspaceService.dispose(tenantId, projectName);
            boolean released = projectManager.releaseLocalLease(tenantId, projectName);
            log.info(
                    "Project '{}/{}' released by this pod (closed, workspace disposed, released={})",
                    tenantId,
                    projectName,
                    released);
            return released;
        }
        boolean released = projectManager.releaseLocalLease(tenantId, projectName);
        log.info(
                "Project '{}/{}' released by this pod (status unchanged, released={})",
                tenantId,
                projectName,
                released);
        return released;
    }

    private boolean isClosed(String tenantId, String projectName) {
        return projectService
                .findByTenantAndName(tenantId, projectName)
                .map(d -> d.getStatus() == ProjectStatus.CLOSED)
                .orElse(false);
    }

    /**
     * Stops a project on purpose: desired state SUSPENDED, engines down, lease
     * dropped. The workspace folder is left untouched. Only an explicit start
     * ({@link #bring} from a session create or an admin resume) brings it
     * back — implicit access does not.
     *
     * <p>Idempotent on an already-SUSPENDED project that runs nowhere.
     */
    public ProjectDocument suspend(String tenantId, String projectName) {
        return stopWithIntent(tenantId, projectName, ProjectStatus.SUSPENDED, /*suspendSessions*/ false);
    }

    /**
     * Stops a project because its users stayed away: desired state IDLE, engines
     * down, lease dropped, open sessions suspended. The same teardown as
     * {@link #suspend}; the difference is who may undo it — a user opening the
     * project wakes an idle one ({@link ProjectLocator#locateForUser}), events and
     * schedulers do not.
     *
     * <p>Works from any pod: the engines are stopped wherever the project runs,
     * see {@link #stopWhereRunning}. Idempotent on an already-IDLE project that
     * runs nowhere.
     */
    public ProjectDocument idle(String tenantId, String projectName) {
        return stopWithIntent(tenantId, projectName, ProjectStatus.IDLE, /*suspendSessions*/ true);
    }

    /**
     * Shared teardown of {@link #suspend} and {@link #idle}. The desired state
     * is written first — it is the part that must survive a crash — and the
     * engines are stopped wherever the project runs; releasing the lease
     * records the actual state as STOPPED. A crash in between leaves a stored
     * STOPPING that no live lease backs, which reads as STOPPED, so nothing
     * needs repairing.
     *
     * <p>Without the teardown the holder would keep renewing the lease of a
     * project that is meant to be down, and keep its engines loaded.
     */
    private ProjectDocument stopWithIntent(
            String tenantId, String projectName, ProjectStatus intent, boolean suspendSessions) {
        ProjectDocument doc = projectService
                .findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '" + tenantId + "'"));
        if (ProjectService.isHomeless(projectName)) {
            // Never brought, so there is nothing to stop.
            log.debug("Homeless project '{}/{}' — {} is a no-op", tenantId, projectName, intent);
            return doc;
        }
        if (doc.getStatus() == ProjectStatus.CLOSED) {
            throw new ProjectService.ProjectStatusConflictException(
                    "Project '" + projectName + "' is CLOSED — cannot set it " + intent);
        }
        boolean changed = doc.getStatus() != intent;
        if (changed) {
            doc = projectService.transitionStatus(tenantId, projectName, doc.getStatus(), intent);
        }
        boolean runsSomewhere = activationRegistry.isActive(tenantId, projectName)
                || ProjectOwnership.liveOwnerPodId(doc, Instant.now(), clusterService.leaseTtl())
                        .isPresent();
        if (!changed && !runsSomewhere) {
            log.debug("Project '{}/{}' already {}", tenantId, projectName, intent);
            return doc;
        }
        stopWhereRunning(doc, suspendSessions);
        log.info("Project '{}/{}' is now {}", tenantId, projectName, intent);
        return projectService.findByTenantAndName(tenantId, projectName).orElse(doc);
    }

    /**
     * Reopen an archived project — the way back from {@link #close}.
     *
     * <p>Checks the active-project quota, flips CLOSED → INIT, restores the
     * group the project was archived from (ungrouped when that group no
     * longer exists), then places it exactly like a fresh {@link #create}.
     * The workspace close disposed stays gone: placement brings the project
     * up with an empty one. Documents, settings, sessions and roles were never
     * touched by the close and are all there.
     *
     * @throws ProjectService.ProjectNotFoundException if the project does not exist
     * @throws ProjectService.ProjectStatusConflictException if it is not archived
     * @throws ProjectQuotaService.ProjectQuotaExceededException if the tenant is at its limit
     */
    public ProjectDocument reopen(String tenantId, String projectName) {
        ProjectDocument current = projectService
                .findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '" + tenantId + "'"));
        if (current.getStatus() != ProjectStatus.CLOSED) {
            throw new ProjectService.ProjectStatusConflictException("Project '" + projectName + "' is "
                    + current.getStatus() + " — only an archived project can be reopened");
        }
        quotaService.checkOneMore(tenantId);
        String origin = current.getArchivedFromGroupId();
        String group = origin != null && projectGroupService.existsByTenantAndName(tenantId, origin) ? origin : null;
        ProjectDocument reopened = projectService.reopen(tenantId, projectName, group);
        try {
            // CREATE, not a trigger of its own: a reopened project is in the
            // same position as a new one — it wants to run, and the user who
            // asked is talking to this pod.
            placementService.place(reopened, PlacementTrigger.CREATE);
        } catch (ClusterFullException e) {
            log.info(
                    "Project '{}/{}' reopened but not placed ({}) — waiting for a matching pod",
                    tenantId,
                    projectName,
                    e.getGap());
        }
        return projectService
                .findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' vanished during reopen"));
    }

    /**
     * Terminate a project: mark CLOSED and move to {@code closedGroupId}, stop
     * its engines wherever it runs, then dispose the workspace folder and the project RAG.
     *
     * <p>Order is load-bearing. CLOSED comes first because a claim on a CLOSED
     * project is refused: from that write on, no pod can bring the project up
     * again while it is being torn down. The engines stop next — on this pod
     * through {@link ProjectEnginesStopRequested}, on another pod through that
     * pod's release ({@link #stopWhereRunning}). The workspace goes last; a remote
     * release still running when this dispose happens (its request timed out
     * here) sees the CLOSED status and disposes as well, so
     * nothing it leaves behind survives the archive.
     *
     * <p>Refuses SYSTEM-kind projects before anything is touched.
     */
    public ProjectDocument close(String tenantId, String projectName, String closedGroupId) {
        if (projectName.startsWith(ProjectService.SYSTEM_NAME_PREFIX)) {
            throw new ProjectService.SystemProjectProtectedException(
                    "Project '" + projectName + "' is a system project — cannot close");
        }
        ProjectDocument doc = projectService.close(tenantId, projectName, closedGroupId);
        stopWhereRunning(doc, /*suspendSessions*/ true);
        workspaceService.dispose(tenantId, projectName);
        disposeProjectRag(tenantId, projectName);
        log.info("Project '{}/{}' closed → group '{}'", tenantId, projectName, closedGroupId);
        return doc;
    }

    /**
     * Stops a project's engines on the pod that runs it — after a close, an idle
     * or a suspend. Only the holder can do that — engines, scheduler entries and hooks live in its
     * process memory — so a close that lands elsewhere has to reach it.
     *
     * <ul>
     *   <li><b>This pod</b> (live lease or locally activated): interrupt and
     *       suspend the open sessions, stop event, deactivate, drop the
     *       lease.</li>
     *   <li><b>Another pod:</b> that pod's {@code /internal/cluster/release}
     *       with {@code suspendSessions}, which does the same there and
     *       removes the folder.</li>
     *   <li><b>Holder not reachable:</b> the sessions are interrupted through
     *       the halt flag (it lives in Mongo) and suspended off-lane, and its
     *       lease is taken away. The
     *       holder's next renewal finds one project fewer than it activated and
     *       unloads it on its own ({@link ProjectLeaseService}) — later, but
     *       without a network path. The folder it leaves is the orphan-storage
     *       sweep's.</li>
     * </ul>
     *
     * <p>Never throws: the desired state is already written (CLOSED, IDLE or
     * SUSPENDED), and a failed teardown must not undo or block what follows it.
     *
     * @param suspendSessions whether the project's open sessions are interrupted
     *     and suspended on the way — an archive and an idle project do, a plain
     *     suspend leaves them
     */
    private void stopWhereRunning(ProjectDocument doc, boolean suspendSessions) {
        String tenantId = doc.getTenantId();
        String projectName = doc.getName();
        String selfPodId = clusterService.selfPodId();
        Optional<String> owner = ProjectOwnership.liveOwnerPodId(doc, Instant.now(), clusterService.leaseTtl());
        if (activationRegistry.isActive(tenantId, projectName)
                || owner.filter(selfPodId::equals).isPresent()) {
            if (suspendSessions) {
                suspendSessionsHere(tenantId, projectName);
            }
            recordActualState(tenantId, projectName, ProjectActualState.STOPPING);
            eventPublisher.publishEvent(new ProjectEnginesStopRequested(tenantId, projectName));
            activationRegistry.deactivate(tenantId, projectName);
            projectManager.releaseLocalLease(tenantId, projectName);
            return;
        }
        if (owner.isEmpty()) {
            return;
        }
        String ownerPodId = owner.get();
        Optional<String> endpoint = clusterService.resolveEndpointByPodId(ownerPodId);
        if (endpoint.isPresent()) {
            try {
                if (bringClient.requestRelease(endpoint.get(), tenantId, projectName, suspendSessions)) {
                    log.info(
                            "Project '{}/{}' stop: engines stopped on home pod '{}'",
                            tenantId,
                            projectName,
                            doc.getHomeNode());
                    return;
                }
                // 409: the pod says it does not hold the project although the
                // lease we read names it. Taking the lease below is guarded on
                // that pod, so it is harmless if the answer was right.
                log.info(
                        "Project '{}/{}' stop: home pod '{}' does not hold it — taking the lease",
                        tenantId,
                        projectName,
                        doc.getHomeNode());
            } catch (ClusterBringClient.ClusterBringException e) {
                log.warn(
                        "Project '{}/{}' stop: home pod '{}' not reachable ({}) — taking its lease instead",
                        tenantId,
                        projectName,
                        doc.getHomeNode(),
                        e.getMessage());
            }
        }
        if (suspendSessions) {
            try {
                sessionLifecycleProvider.getObject().suspendProjectSessionsOffLane(tenantId, projectName);
            } catch (RuntimeException e) {
                log.warn(
                        "Project '{}/{}' stop: off-lane session suspend failed: {}",
                        tenantId,
                        projectName,
                        e.toString());
            }
        }
        projectService.releaseLease(
                tenantId,
                projectName,
                ownerPodId,
                doc.getHomeNode() == null ? ownerPodId : doc.getHomeNode(),
                endpoint.orElse(""));
        projectLive.record(tenantId, projectName, ProjectLiveState.STOPPED);
    }

    /**
     * Interrupt and suspend the project's open sessions on this pod's lanes.
     * Failure is logged, not thrown: the archive (or release) it belongs to
     * must still stop the engines and move on.
     */
    private void suspendSessionsHere(String tenantId, String projectName) {
        try {
            sessionLifecycleProvider.getObject().suspendProjectSessions(tenantId, projectName);
        } catch (RuntimeException e) {
            log.warn("Project '{}/{}': session suspend failed: {}", tenantId, projectName, e.toString());
        }
    }

    /**
     * Best-effort {@code ensureDefaultRag} during bring. A misconfigured
     * embedding provider (no API key, unknown model) must not block the
     * project from reaching RUNNING — the user can fix the setting and
     * trigger reindex from the UI. Logs at warn, never throws.
     */
    private void ensureProjectRag(String tenantId, String projectName) {
        ProjectRagService rag = projectRagProvider.getIfAvailable();
        if (rag == null) return;
        try {
            rag.ensureDefaultRag(tenantId, projectName);
        } catch (RuntimeException e) {
            log.warn(
                    "Project-RAG ensureDefaultRag failed for '{}/{}' — continuing: {}",
                    tenantId,
                    projectName,
                    e.toString());
        }
    }

    /** Best-effort dispose during close — same tolerance reasoning. */
    private void disposeProjectRag(String tenantId, String projectName) {
        ProjectRagService rag = projectRagProvider.getIfAvailable();
        if (rag == null) return;
        try {
            rag.disposeDefaultRag(tenantId, projectName);
        } catch (RuntimeException e) {
            log.warn("Project-RAG disposeDefaultRag failed for '{}/{}': {}", tenantId, projectName, e.toString());
        }
    }
}
