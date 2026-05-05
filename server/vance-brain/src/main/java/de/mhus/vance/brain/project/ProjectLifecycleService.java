package de.mhus.vance.brain.project;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
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
    private final WorkspaceService workspaceService;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;
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
            ProjectKind kind) {
        projectService.create(tenantId, name, title, projectGroupId, teamIds, kind);
        return bring(tenantId, name);
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
     * Move a project on this pod from any non-CLOSED status to RUNNING:
     * claim the pod, transition to RECOVERING, restore the workspace
     * (auto-recovers from snapshots if any), publish
     * {@link ProjectEnginesStartRequested}, transition to RUNNING.
     * Idempotent — repeated calls on a RUNNING project just refresh
     * the pod claim.
     */
    public ProjectDocument bring(String tenantId, String projectName) {
        ProjectDocument doc = projectManager.claimForLocalPod(tenantId, projectName);
        if (doc.getStatus() == ProjectStatus.RUNNING) {
            log.debug("Project '{}/{}' already RUNNING — claim refreshed", tenantId, projectName);
            return doc;
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
        eventPublisher.publishEvent(new ProjectEnginesStartRequested(tenantId, projectName));
        doc = projectService.transitionStatus(
                tenantId, projectName, ProjectStatus.RECOVERING, ProjectStatus.RUNNING);
        log.info("Project '{}/{}' brought to RUNNING (was {})", tenantId, projectName, from);
        return doc;
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
        workspaceService.dispose(tenantId, projectName);
        ProjectDocument doc = projectService.close(tenantId, projectName, closedGroupId);
        log.info("Project '{}/{}' closed → group '{}'", tenantId, projectName, closedGroupId);
        return doc;
    }
}
