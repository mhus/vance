package de.mhus.vance.brain.project;

import de.mhus.vance.brain.cluster.placement.ClusterFullException;
import de.mhus.vance.brain.cluster.placement.PlacementTrigger;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.rag.ProjectRagService;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 *   <li>{@link #bring} — claim pod, recover workspace, mark RUNNING
 *       (and request engines start).</li>
 *   <li>{@link #suspend} — request engines stop, suspend workspace,
 *       mark SUSPENDED.</li>
 *   <li>{@link #close} — dispose workspace, mark CLOSED, move to the
 *       archived group.</li>
 * </ul>
 *
 * <p>Engine start/stop is signalled via Spring events
 * ({@link ProjectEnginesStartRequested} / {@link ProjectEnginesStopRequested}).
 * V1 has no listeners — engine cleanup is operator-driven. Listeners
 * land with the session-lifecycle work.
 *
 * <p>Crash recovery: every transition is idempotent. {@link #bring}
 * re-runs through RECOVERING from any non-CLOSED status; {@link #suspend}
 * picks up from SUSPENDING.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectLifecycleService {

    private final ProjectService projectService;
    private final ProjectManagerService projectManager;
    private final ProjectPlacementService placementService;
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
     * Optional permission-bootstrap SPI. Present only when a provider addon
     * that stores grants in Vance (simple-auth) is loaded; then the creator
     * of a project is seeded as its PROJECT-ADMIN. Absent under the allow-all
     * provider or an external governor — {@code ifAvailable} makes the seed a
     * no-op there. See {@code planning/permission-system-concept.md} §7.0.
     */
    private final ObjectProvider<de.mhus.vance.shared.permission.PermissionBootstrap>
            permissionBootstrapProvider;

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
     */
    public ProjectDocument create(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable String projectGroupId,
            @Nullable List<String> teamIds,
            ProjectKind kind,
            @Nullable String createdBy) {
        ProjectDocument created =
                projectService.create(tenantId, name, title, projectGroupId, teamIds, kind);
        // Where it runs is the placement service's call — local-first when this
        // pod has room, otherwise the least-loaded pod that does. HOMELESS and
        // podless projects short-circuit to a local bring inside it.
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
            log.info("Project '{}/{}' created but not placed ({}) — waiting for a matching pod",
                    tenantId, name, e.getGap());
        }
        // Re-read: the bring behind place() moved the lease and the status.
        ProjectDocument saved = projectService.findByTenantAndName(tenantId, name)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + name + "' vanished during create"));
        // Seed the creator as PROJECT-ADMIN so they can manage the project
        // they just made. No-op unless a grant-storing provider is loaded.
        if (createdBy != null && !createdBy.isBlank() && kind != ProjectKind.SYSTEM) {
            permissionBootstrapProvider.ifAvailable(
                    pb -> pb.grantProjectAdmin(tenantId, saved.getName(), createdBy));
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
        ProjectDocument project = projectService.findByTenantAndName(req.tenantId(), req.projectName())
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + req.projectName() + "' not found in tenant '"
                                + req.tenantId() + "'"));

        SessionDocument session = sessionService.create(
                req.tenantId(),
                req.userId(),
                project.getName(),
                req.displayName(),
                req.profile(),
                req.clientVersion(),
                req.clientName());

        ThinkProcessDocument chat = chatBootstrapperProvider.getObject()
                .ensureChatProcess(session, req.parentProcessId())
                .orElseThrow(() -> new IllegalStateException(
                        "Chat-process bootstrap failed for session '" + session.getSessionId() + "'"));

        if (req.initialPrompt() != null && !req.initialPrompt().isBlank()) {
            PendingMessageDocument msg = PendingMessageDocument.builder()
                    .type(PendingMessageType.USER_CHAT_INPUT)
                    .at(Instant.now())
                    .fromUser(req.senderProcessId() == null
                            ? req.userId()
                            : "process:" + req.senderProcessId())
                    .content(req.initialPrompt())
                    .build();
            boolean ok = messageRouterProvider.getObject()
                    .dispatch(req.senderProcessId(), chat.getId(), msg);
            if (!ok) {
                log.warn("bootstrapChat: initialPrompt dispatch failed for chat='{}'", chat.getId());
            }
        }

        log.info("bootstrapChat: tenant='{}' project='{}' session='{}' chat='{}' parent='{}' withPrompt={}",
                req.tenantId(), project.getName(), session.getSessionId(),
                chat.getId(), req.parentProcessId(),
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
    public record BootstrapResult(
            ProjectDocument project,
            SessionDocument session,
            ThinkProcessDocument chatProcess) {}

    /**
     * Move a project onto this pod: take the lease, transition to RECOVERING,
     * restore the workspace (auto-recovers from snapshots if any), publish
     * {@link ProjectEnginesStartRequested}, transition to RUNNING.
     *
     * <p><b>Idempotence keys on this pod's activation registry, not on
     * {@code status}.</b> The short-circuit needs "already running
     * <em>here</em>", and {@code status} cannot answer that — it is shared,
     * and after a crash it still says {@code RUNNING} because the only thing
     * that ever writes it back is an explicit admin {@link #suspend}. Reading
     * it as "nothing to do" meant the new lease holder owned the project
     * without ever starting anything for it: no workspace, no session unbind,
     * and no {@link ProjectEnginesStartRequested}, so scheduler, hooks, tool
     * preload and kit provisioning stayed dark until somebody noticed and
     * added a private trigger. That is the defect this whole track is about
     * ({@code planning/project-ownership-lease-design.md} §1.2).
     *
     * <p>Consequence, intended: a {@code RUNNING} project whose lease expired
     * is the <em>normal</em> recovery case and runs the full pass, including
     * the RUNNING → RECOVERING → RUNNING round trip.
     *
     * <p>Podless system projects (see {@link ProjectService#isPodless})
     * skip the lease and the status transitions: their lifecycle is
     * tied to the WS connection, not to ownership. The local
     * workspace is still initialised and engines are still started so
     * Eddie has a usable scratch area on this pod.
     */
    public ProjectDocument bring(String tenantId, String projectName) {
        if (ProjectService.isPodless(projectName)) {
            return bringPodless(tenantId, projectName);
        }
        ProjectDocument doc = projectManager.claimForLocalPod(tenantId, projectName);
        if (doc.getStatus() == ProjectStatus.RUNNING
                && activationRegistry.isActive(tenantId, projectName)) {
            // Already up *on this pod*: lease refreshed, nothing to do.
            log.debug("Project '{}/{}' already active here — lease refreshed",
                    tenantId, projectName);
            return doc;
        }
        // Bring = the project is coming online on *this* pod. No client can be
        // legitimately bound right now — either the previous holder died (and
        // its WS connections died with it), or the project was suspended and
        // the workspace was off-disk. Stale boundConnectionId values would
        // otherwise reject the first reconnect with 409 Already-Bound. This
        // covers every path: self-pull, distributor, locator, direct-spawn.
        long unbound = sessionService.unbindAllForProjects(List.of(projectName));
        if (unbound > 0) {
            log.info("Project '{}/{}' bring: cleared {} stale session binding(s)",
                    tenantId, projectName, unbound);
        }
        ProjectStatus from = doc.getStatus();
        doc = projectService.transitionStatus(tenantId, projectName, from, ProjectStatus.RECOVERING);
        try {
            workspaceService.init(tenantId, projectName);
        } catch (RuntimeException e) {
            log.error("Workspace init failed for '{}/{}' (status remains RECOVERING): {}",
                    tenantId, projectName, e.toString());
            throw e;
        }
        ensureProjectRag(tenantId, projectName);
        eventPublisher.publishEvent(new ProjectEnginesStartRequested(tenantId, projectName));
        // Registered after the start event, so a listener that throws leaves
        // the project un-activated and the next bring retries the whole pass
        // rather than short-circuiting on a half-started project.
        activationRegistry.activate(tenantId, projectName);
        doc = projectService.transitionStatus(
                tenantId, projectName, ProjectStatus.RECOVERING, ProjectStatus.RUNNING);
        log.info("Project '{}/{}' brought to RUNNING (was {})", tenantId, projectName, from);
        return doc;
    }

    private ProjectDocument bringPodless(String tenantId, String projectName) {
        ProjectDocument doc = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '"
                                + tenantId + "'"));
        try {
            workspaceService.init(tenantId, projectName);
        } catch (RuntimeException e) {
            log.error("Workspace init failed for podless project '{}/{}': {}",
                    tenantId, projectName, e.toString());
            throw e;
        }
        eventPublisher.publishEvent(new ProjectEnginesStartRequested(tenantId, projectName));
        // Podless projects hold no lease, but they *are* activated here — their
        // schedulers and hooks live on this pod. The activation-gated document
        // listeners need to know that, otherwise a scheduler edit in
        // _user_<login> or _vance would be ignored on the very pod running it.
        // No short-circuit is derived from this: the branch above returns
        // before bring's registry check, so a repeated bring still re-inits.
        activationRegistry.activate(tenantId, projectName);
        log.debug("Podless project '{}/{}' brought up locally (no lease, status unchanged)",
                tenantId, projectName);
        return doc;
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
     *   <li>This one still holds the lease while it runs, so the workspace
     *       <em>does</em> travel: snapshot first, release after. Skipping the
     *       snapshot would hand the next owner whatever the last snapshot
     *       happened to be, silently losing work in progress — the whole point
     *       of a drain is that the project continues elsewhere.</li>
     * </ul>
     *
     * <p>Order is load-bearing: deactivate, stop engines, snapshot, <em>then</em>
     * release. Releasing first would let another pod claim and initialise from
     * the old snapshot while this one is still writing the new one.
     *
     * <p>Podless projects hold no lease and are refused — there is nothing to
     * hand over, and their lifecycle follows the WS connection.
     *
     * @return {@code false} when this pod does not hold the lease, so a caller
     *     can answer 409 rather than pretend something happened
     */
    public boolean release(String tenantId, String projectName) {
        if (ProjectService.isPodless(projectName)) {
            throw new ProjectService.SystemProjectProtectedException(
                    "Project '" + projectName + "' is podless — it holds no lease to release");
        }
        if (!activationRegistry.isActive(tenantId, projectName)
                && !projectManager.isOwnedByLocalPod(tenantId, projectName)) {
            log.debug("Project '{}/{}' release: not held here", tenantId, projectName);
            return false;
        }
        eventPublisher.publishEvent(new ProjectEnginesStopRequested(tenantId, projectName));
        activationRegistry.deactivate(tenantId, projectName);
        try {
            workspaceService.suspendAll(tenantId, projectName);
        } catch (RuntimeException e) {
            // Keep the lease: a project whose workspace we could not snapshot
            // must not be handed to a pod that would then restore an older one.
            log.error("Project '{}/{}' release aborted — workspace snapshot failed, "
                    + "keeping the lease: {}", tenantId, projectName, e.toString());
            throw e;
        }
        boolean released = projectManager.releaseLocalLease(tenantId, projectName);
        log.info("Project '{}/{}' released by this pod (status unchanged, released={})",
                tenantId, projectName, released);
        return released;
    }

    /**
     * Move a RUNNING project to SUSPENDED: transition to SUSPENDING,
     * publish {@link ProjectEnginesStopRequested}, suspend the workspace
     * (snapshots → Mongo, folder gone), transition to SUSPENDED. Picks
     * up from SUSPENDING if a previous attempt crashed mid-flow.
     * Idempotent on already-SUSPENDED projects.
     */
    public ProjectDocument suspend(String tenantId, String projectName) {
        ProjectDocument doc = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '" + tenantId + "'"));
        if (ProjectService.isPodless(projectName)) {
            // Podless projects (e.g. _user_<login>, _vance) are pod-local
            // and ephemeral — there is nothing to snapshot to Mongo and
            // no pod-affinity to release. Engine teardown happens via
            // SessionLifecycleService cascades.
            log.debug("Podless project '{}/{}' — suspend is a no-op",
                    tenantId, projectName);
            return doc;
        }
        switch (doc.getStatus()) {
            case SUSPENDED -> {
                log.debug("Project '{}/{}' already SUSPENDED", tenantId, projectName);
                return doc;
            }
            case CLOSED -> throw new ProjectService.ProjectStatusConflictException(
                    "Project '" + projectName + "' is CLOSED — cannot suspend");
            case SUSPENDING -> log.info(
                    "Project '{}/{}' was in SUSPENDING (prior crash) — completing suspend",
                    tenantId, projectName);
            default -> {
                doc = projectService.transitionStatus(
                        tenantId, projectName, doc.getStatus(), ProjectStatus.SUSPENDING);
            }
        }
        eventPublisher.publishEvent(new ProjectEnginesStopRequested(tenantId, projectName));
        // Symmetric to the activate() in bring: this pod no longer has the
        // project up, so the next bring must run the full pass again.
        activationRegistry.deactivate(tenantId, projectName);
        try {
            workspaceService.suspendAll(tenantId, projectName);
        } catch (RuntimeException e) {
            log.error("Workspace suspendAll failed for '{}/{}' (status remains SUSPENDING): {}",
                    tenantId, projectName, e.toString());
            throw e;
        }
        doc = projectService.transitionStatus(
                tenantId, projectName, ProjectStatus.SUSPENDING, ProjectStatus.SUSPENDED);
        log.info("Project '{}/{}' suspended", tenantId, projectName);
        return doc;
    }

    /**
     * Terminate a project: dispose the workspace (folder + snapshots
     * gone), then mark CLOSED and move to {@code closedGroupId}.
     * Refuses SYSTEM-kind projects (delegated check via
     * {@link ProjectService#close}). Engine teardown is the caller's
     * responsibility — close does not emit
     * {@link ProjectEnginesStopRequested} (use {@link #suspend} first
     * if needed).
     */
    public ProjectDocument close(String tenantId, String projectName, String closedGroupId) {
        if (ProjectService.isPodless(projectName)) {
            // Podless projects are also SYSTEM-kind, so projectService.close
            // would throw SystemProjectProtectedException anyway — but doing
            // it here surfaces the right reason and skips the pointless
            // workspaceService.dispose that runs before that check.
            throw new ProjectService.SystemProjectProtectedException(
                    "Project '" + projectName + "' is a podless system project — cannot close");
        }
        workspaceService.dispose(tenantId, projectName);
        disposeProjectRag(tenantId, projectName);
        // A closed project cannot be brought back, so leaving it in the
        // registry would make the lease reconciler count a project that can
        // never hold a lease again.
        activationRegistry.deactivate(tenantId, projectName);
        ProjectDocument doc = projectService.close(tenantId, projectName, closedGroupId);
        log.info("Project '{}/{}' closed → group '{}'", tenantId, projectName, closedGroupId);
        return doc;
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
            log.warn("Project-RAG ensureDefaultRag failed for '{}/{}' — continuing: {}",
                    tenantId, projectName, e.toString());
        }
    }

    /** Best-effort dispose during close — same tolerance reasoning. */
    private void disposeProjectRag(String tenantId, String projectName) {
        ProjectRagService rag = projectRagProvider.getIfAvailable();
        if (rag == null) return;
        try {
            rag.disposeDefaultRag(tenantId, projectName);
        } catch (RuntimeException e) {
            log.warn("Project-RAG disposeDefaultRag failed for '{}/{}': {}",
                    tenantId, projectName, e.toString());
        }
    }
}
