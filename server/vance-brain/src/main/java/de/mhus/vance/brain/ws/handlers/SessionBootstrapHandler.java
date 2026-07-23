package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.thinkprocess.BootstrappedProcess;
import de.mhus.vance.api.thinkprocess.ProcessSpec;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.thinkprocess.SessionBootstrapResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.inbox.InboxPendingSummaryPusher;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Compound command: session (create or resume) + zero or more
 * think-processes + optional initial steer — all in one round-trip.
 *
 * <p>Runs on a session-less connection (creates/binds a session). If
 * the request carries a {@code sessionId}, the session is resumed;
 * otherwise a new one is created on {@code projectId} (or auto-resumed
 * from the user's most-recent OPEN+unbound session when neither is
 * supplied).
 *
 * <p>Processes are built in list order. Each spec is dispatched through
 * the central {@link ActionExecutorRegistry} as a
 * {@link TriggerAction.Recipe} with caller-supplied name/title/goal and
 * the session's connection profile. Name-collisions against an existing
 * process in the session are reported as {@code processesSkipped} —
 * idempotent for resume scenarios.
 *
 * <p>{@code initialMessage}, when set, is steered to the first
 * process in the list via the lane scheduler (fire-and-forget) — the
 * WS response goes out immediately and the {@code chat-message-appended}
 * notification arrives once the lane has processed the steer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionBootstrapHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final ProjectService projectService;
    private final ProjectManagerService projectManager;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final SessionConnectionRegistry connectionRegistry;
    private final de.mhus.vance.brain.events.SessionRosterBroadcaster rosterBroadcaster;
    private final SessionChatBootstrapper chatBootstrapper;
    private final InboxPendingSummaryPusher inboxSummaryPusher;
    private final HomeBootstrapService homeBootstrapService;
    private final RequestAuthority authority;
    private final ActionExecutorRegistry actionRegistry;
    private final LaneScheduler laneScheduler;

    @Override
    public String type() {
        return MessageType.SESSION_BOOTSTRAP;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return !ctx.hasSession();
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        SessionBootstrapRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), SessionBootstrapRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid session-bootstrap payload: " + e.getMessage());
            return;
        }
        if (request == null) {
            sender.sendError(wsSession, envelope, 400, "empty session-bootstrap payload");
            return;
        }
        // Compound entry-point — gate at the tenant level. Per-project /
        // per-session checks happen inside resumeAndBindSession / spawn paths.
        authority.enforce(ctx, new Resource.Tenant(ctx.getTenantId()), Action.START);

        // ── Session: explicit resume / auto-resume / create ──────────────
        SessionDocument session;
        boolean sessionCreated;
        try {
            if (!isBlank(request.getSessionId())) {
                Optional<SessionDocument> resumed = resumeAndBindSession(ctx, wsSession, envelope, request);
                if (resumed.isEmpty()) return;
                session = resumed.get();
                sessionCreated = false;
            } else if (isBlank(request.getProjectId())) {
                Optional<SessionDocument> autoResumed = tryAutoResumeLatest(ctx);
                if (autoResumed.isPresent()) {
                    session = autoResumed.get();
                    sessionCreated = false;
                    log.info("session-bootstrap: auto-resumed '{}' (tenant='{}' user='{}')",
                            session.getSessionId(), ctx.getTenantId(), ctx.getUserId());
                } else {
                    Optional<SessionDocument> created = createAndBindSession(ctx, wsSession, envelope, request);
                    if (created.isEmpty()) return;
                    session = created.get();
                    sessionCreated = true;
                }
            } else {
                Optional<SessionDocument> created = createAndBindSession(ctx, wsSession, envelope, request);
                if (created.isEmpty()) return;
                session = created.get();
                sessionCreated = true;
            }
        } catch (RuntimeException e) {
            log.error("Session bootstrap failed during session step for tenant='{}' user='{}'",
                    ctx.getTenantId(), ctx.getUserId(), e);
            sender.sendError(wsSession, envelope, 500,
                    "Session step failed: " + e.getMessage());
            return;
        }
        ctx.bindSession(session);
        SessionConnectionRegistry.RegisterResult registerResult = connectionRegistry.register(
                session.getSessionId(),
                ctx.getUserId(),
                ctx.getEditorId(),
                ctx.getDisplayName(),
                wsSession,
                session.isAllowMultipleClients());
        if (registerResult.outcome() == SessionConnectionRegistry.RegisterOutcome.REJECTED) {
            // Defensive: bind paths above gate same-user-only access, so
            // this should be unreachable for a private session. If we
            // land here anyway, surface a 409 instead of running the
            // bootstrap chain on a half-bound state.
            sender.sendError(wsSession, envelope, 409,
                    "Session '" + session.getSessionId()
                            + "' is private and already held by another user");
            ctx.unbindSession();
            return;
        }
        SessionConnectionRegistry.closeKicked(registerResult);
        // Initial roster push — see SessionCreateHandler for details.
        if (session.isAllowMultipleClients()) {
            rosterBroadcaster.sendInitialRoster(session.getSessionId(), wsSession);
        }
        inboxSummaryPusher.pushIfAny(wsSession, ctx.getTenantId(), ctx.getUserId());

        // ── Auto-spawn the session-chat process ──────────────────────────
        // Idempotent: re-bootstrap of an existing session adopts the chat
        // process that's already there. Failure here doesn't fail the
        // whole bootstrap — log and leave chatProcess null in the response.
        ThinkProcessDocument chatProcess = null;
        try {
            chatProcess = chatBootstrapper.ensureChatProcess(
                    session, /*parentProcessId*/ null,
                    request.getChatRecipe()).orElse(null);
        } catch (RuntimeException e) {
            log.error("Chat-process bootstrap failed for session '{}'",
                    session.getSessionId(), e);
        }

        // ── Processes: spawn-via-pipeline (skip duplicates) ──────────────
        List<BootstrappedProcess> created = new ArrayList<>();
        List<BootstrappedProcess> skipped = new ArrayList<>();
        List<ProcessSpec> processes = request.getProcesses() != null
                ? request.getProcesses() : List.<ProcessSpec>of();
        ThinkProcessDocument firstProcess = null;

        for (ProcessSpec spec : processes) {
            if (spec == null || isBlank(spec.getName())) {
                sender.sendError(wsSession, envelope, 400, "process spec needs name");
                return;
            }
            Optional<ThinkProcessDocument> existing = thinkProcessService
                    .findByName(session.getTenantId(), session.getSessionId(), spec.getName());
            if (existing.isPresent()) {
                ThinkProcessDocument doc = existing.get();
                skipped.add(toBootstrapped(doc));
                if (firstProcess == null) firstProcess = doc;
                continue;
            }

            TriggerAction.Recipe action = new TriggerAction.Recipe(
                    spec.getRecipe(),
                    spec.getName(),
                    spec.getTitle(),
                    spec.getGoal(),
                    /*inheritContextLevel*/ null,
                    session.getProfile(),
                    /*initialMessage*/ null,
                    spec.getParams(),
                    /*runAs*/ null);
            TriggerContext triggerCtx = TriggerContext.sessioned(
                    session.getTenantId(), session.getProjectId(),
                    /*resolvedRunAs*/ null, /*correlationId*/ null,
                    /*sourceTag*/ "session-bootstrap",
                    session.getSessionId(), /*parentProcessId*/ null);

            ActionResult result = actionRegistry.execute(action, triggerCtx, TriggerKind.USER);
            switch (result.outcome()) {
                case SCHEDULED -> {
                    ThinkProcessDocument fresh = thinkProcessService.findById(result.spawnedId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "spawned process '" + result.spawnedId() + "' is gone"));
                    created.add(toBootstrapped(fresh));
                    if (firstProcess == null) firstProcess = fresh;
                }
                case SUCCESS -> {
                    // Soft-success: already_exists race — adopt the existing one.
                    String existingId = result.output() == null ? null
                            : (String) result.output().get("existingProcessId");
                    if (existingId != null) {
                        thinkProcessService.findById(existingId).ifPresent(doc -> {
                            skipped.add(toBootstrapped(doc));
                        });
                        if (firstProcess == null && existingId != null) {
                            thinkProcessService.findById(existingId).ifPresent(d -> {});
                        }
                    }
                }
                case TECHNICAL_ERROR, BUSINESS_ERROR, TIMEOUT, PERMISSION_ERROR, CANCELLED -> {
                    int status = result.errorMessage() != null
                            && result.errorMessage().toLowerCase().contains("unknown recipe")
                            ? 404 : 500;
                    sender.sendError(wsSession, envelope, status,
                            "process spec '" + spec.getName() + "' failed: "
                                    + result.errorMessage());
                    return;
                }
            }
        }

        // ── Optional initial steer (fire-and-forget on the lane) ─────────
        // Q18 fix: previously the WS handler thread ran engine.steer
        // synchronously, blocking the response. The lane scheduler
        // processes it asynchronously; chat-message-appended frames
        // arrive once the engine emits them.
        String steeredProcessName = null;
        if (!isBlank(request.getInitialMessage()) && firstProcess != null) {
            ThinkProcessDocument target = firstProcess;
            SteerMessage.UserChatInput userInput = new SteerMessage.UserChatInput(
                    Instant.now(), null, ctx.getUserId(), request.getInitialMessage());
            try {
                laneScheduler.submit(target.getId(),
                        () -> thinkEngineService.steer(target, userInput));
                steeredProcessName = target.getName();
            } catch (RuntimeException e) {
                log.error("Initial-steer lane-submit failed for process id='{}'",
                        target.getId(), e);
                sender.sendError(wsSession, envelope, 500,
                        "Initial steer submit failed: " + e.getMessage());
                return;
            }
        }

        // Propagate the connection profile to every think-process on the
        // session so the per-turn tool filter (Tool.allowedForProfile)
        // and capability checks see the current bound profile. See
        // engine-message-routing.md §4.1.1.
        thinkProcessService.updateBoundProfileForSession(
                session.getSessionId(), ctx.getProfile());

        SessionBootstrapResponse response = SessionBootstrapResponse.builder()
                .sessionId(session.getSessionId())
                .projectId(session.getProjectId())
                .sessionCreated(sessionCreated)
                .processesCreated(created)
                .processesSkipped(skipped)
                .steeredProcessName(steeredProcessName)
                .chatProcessId(chatProcess == null ? null : chatProcess.getId())
                .chatProcessName(chatProcess == null ? null : chatProcess.getName())
                .chatEngine(chatProcess == null ? null : chatProcess.getThinkEngine())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_BOOTSTRAP, response);
    }

    // ──────────────────── Session sub-steps ────────────────────

    /**
     * Walks the user's OPEN sessions, newest-activity first, and tries
     * to atomically bind each one to this connection. First successful
     * bind wins. Returns {@link Optional#empty()} if the user has no
     * unbound resumable session — the caller will fall through to
     * session creation.
     */
    private Optional<SessionDocument> tryAutoResumeLatest(ConnectionContext ctx) {
        List<SessionDocument> candidates = sessionService
                .listForUser(ctx.getTenantId(), ctx.getUserId()).stream()
                .filter(s -> s.getStatus() != SessionStatus.CLOSED)
                .filter(s -> s.getBoundConnectionId() == null)
                .filter(s -> profileMatches(ctx, s))
                .sorted((a, b) -> {
                    Instant aLa = a.getLastActivityAt();
                    Instant bLa = b.getLastActivityAt();
                    if (aLa == null && bLa == null) return 0;
                    if (aLa == null) return 1;
                    if (bLa == null) return -1;
                    return bLa.compareTo(aLa);
                })
                .toList();
        for (SessionDocument candidate : candidates) {
            if (sessionService.tryBind(
                    candidate.getSessionId(), ctx.getEditorId())) {
                try {
                    projectManager.claimForLocalPod(
                            candidate.getTenantId(), candidate.getProjectId());
                } catch (RuntimeException claimFailed) {
                    sessionService.unbind(candidate.getSessionId(), ctx.getEditorId());
                    throw claimFailed;
                }
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<SessionDocument> createAndBindSession(
            ConnectionContext ctx, WebSocketSession wsSession,
            WebSocketEnvelope envelope, SessionBootstrapRequest request) throws IOException {
        String projectId = request.getProjectId();
        if (isBlank(projectId)) {
            // Default to the caller's first *readable* project — never bind
            // them into one they can't access. Source owns the READ check.
            List<de.mhus.vance.shared.project.ProjectDocument> all =
                    projectService.listReadableBy(ctx.getTenantId(), authority.contextOf(ctx));
            if (all.isEmpty()) {
                sender.sendError(wsSession, envelope, 404,
                        "No projects found in tenant '" + ctx.getTenantId() + "'");
                return Optional.empty();
            }
            projectId = all.get(0).getName();
            log.info("session-bootstrap: defaulted projectId to '{}' (tenant='{}')",
                    projectId, ctx.getTenantId());
        } else if (homeBootstrapService.resolveOrAutoProvision(
                ctx.getTenantId(), projectId).isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Project '" + projectId + "' not found");
            return Optional.empty();
        }
        projectManager.claimForLocalPod(ctx.getTenantId(), projectId);
        SessionDocument fresh = sessionService.create(
                ctx.getTenantId(),
                ctx.getUserId(),
                projectId,
                ctx.getDisplayName(),
                ctx.getProfile(),
                ctx.getClientVersion(),
                ctx.getClientName());
        boolean bound = sessionService.tryBind(
                fresh.getSessionId(), ctx.getEditorId());
        if (!bound) {
            log.warn("Freshly created session '{}' failed to bind", fresh.getSessionId());
            sender.sendError(wsSession, envelope, 500,
                    "Session created but could not be bound — please retry");
            return Optional.empty();
        }
        return Optional.of(fresh);
    }

    private Optional<SessionDocument> resumeAndBindSession(
            ConnectionContext ctx, WebSocketSession wsSession,
            WebSocketEnvelope envelope, SessionBootstrapRequest request) throws IOException {
        Optional<SessionDocument> existing = sessionService.findBySessionId(request.getSessionId());
        if (existing.isEmpty() || existing.get().getStatus() == SessionStatus.CLOSED) {
            sender.sendError(wsSession, envelope, 404,
                    "Session '" + request.getSessionId() + "' not found");
            return Optional.empty();
        }
        SessionDocument doc = existing.get();
        if (!doc.getTenantId().equals(ctx.getTenantId())
                || !doc.getUserId().equals(ctx.getUserId())) {
            sender.sendError(wsSession, envelope, 403,
                    "Session '" + request.getSessionId() + "' belongs to another user");
            return Optional.empty();
        }
        if (!profileMatches(ctx, doc)) {
            sender.sendError(wsSession, envelope, 409,
                    "Session '" + request.getSessionId() + "' was created with profile '"
                            + doc.getProfile() + "', this connection uses profile '"
                            + ctx.getProfile() + "' — start a new session instead");
            return Optional.empty();
        }
        projectManager.claimForLocalPod(doc.getTenantId(), doc.getProjectId());
        // Same-user takeover: the userId-match check above guarantees the
        // existing bind (if any) belongs to the same human. Allow them to
        // resume from a fresh tab/pod without waiting for the previous
        // editor's heartbeat to go stale.
        boolean bound = sessionService.tryBindWithUserTakeover(
                doc.getSessionId(), ctx.getEditorId());
        if (!bound) {
            sender.sendError(wsSession, envelope, 409,
                    "Session '" + doc.getSessionId() + "' is closed or archived");
            return Optional.empty();
        }
        return Optional.of(doc);
    }

    // ──────────────────── Helpers ────────────────────

    private static BootstrappedProcess toBootstrapped(ThinkProcessDocument doc) {
        return BootstrappedProcess.builder()
                .thinkProcessId(doc.getId())
                .name(doc.getName())
                .engine(doc.getThinkEngine())
                .status(doc.getStatus())
                .build();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private static boolean profileMatches(ConnectionContext ctx, SessionDocument session) {
        String sessionProfile = session.getProfile();
        String ctxProfile = ctx.getProfile();
        return sessionProfile != null && sessionProfile.equals(ctxProfile);
    }
}
