package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.thinkprocess.BootstrappedProcess;
import de.mhus.vance.api.thinkprocess.ProcessSpec;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.thinkprocess.SessionBootstrapResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.session.SessionStatus;
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
 * <p>Runs on a session-less connection (creates/binds a session). If the
 * request carries a {@code sessionId}, the session is resumed; otherwise a
 * new one is created on {@code projectId}.
 *
 * <p>Processes are built in list order. Name-collisions against an existing
 * process in the session are reported as {@code processesSkipped} — the
 * operation stays idempotent for resume scenarios. If a single
 * {@code process-create} / {@code engine.start} throws, the handler
 * best-effort continues with the remaining processes and reports the
 * failure via the usual error frame; there is no rollback.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionBootstrapHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final ProjectService projectService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final ChatMessageService chatMessageService;

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

        // ── Session: explicit resume / auto-resume / create ──────────────
        SessionDocument session;
        boolean sessionCreated;
        try {
            if (!isBlank(request.getSessionId())) {
                // Explicit resume of a known session.
                Optional<SessionDocument> resumed = resumeAndBindSession(ctx, wsSession, envelope, request);
                if (resumed.isEmpty()) {
                    return;
                }
                session = resumed.get();
                sessionCreated = false;
            } else if (isBlank(request.getProjectId())) {
                // "Just let me pick up where I left off" — try to resume the
                // most recent unbound OPEN session of this user before
                // defaulting to a fresh one.
                Optional<SessionDocument> autoResumed = tryAutoResumeLatest(ctx);
                if (autoResumed.isPresent()) {
                    session = autoResumed.get();
                    sessionCreated = false;
                    log.info("session-bootstrap: auto-resumed '{}' (tenant='{}' user='{}')",
                            session.getSessionId(), ctx.getTenantId(), ctx.getUserId());
                } else {
                    Optional<SessionDocument> created = createAndBindSession(ctx, wsSession, envelope, request);
                    if (created.isEmpty()) {
                        return;
                    }
                    session = created.get();
                    sessionCreated = true;
                }
            } else {
                // Explicit project name without sessionId — always create.
                Optional<SessionDocument> created = createAndBindSession(ctx, wsSession, envelope, request);
                if (created.isEmpty()) {
                    return;
                }
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

        // ── Processes: create + start (skip duplicates) ──────────────────
        List<BootstrappedProcess> created = new ArrayList<>();
        List<BootstrappedProcess> skipped = new ArrayList<>();
        List<ProcessSpec> processes = request.getProcesses() != null
                ? request.getProcesses() : List.<ProcessSpec>of();
        ThinkProcessDocument firstProcess = null;

        for (ProcessSpec spec : processes) {
            if (spec == null || isBlank(spec.getEngine()) || isBlank(spec.getName())) {
                sender.sendError(wsSession, envelope, 400,
                        "process spec needs engine and name");
                return;
            }
            Optional<ThinkProcessDocument> existing = thinkProcessService
                    .findByName(session.getTenantId(), session.getSessionId(), spec.getName());
            if (existing.isPresent()) {
                ThinkProcessDocument doc = existing.get();
                skipped.add(toBootstrapped(doc));
                if (firstProcess == null) {
                    firstProcess = doc;
                }
                continue;
            }
            Optional<ThinkEngine> engineOpt = thinkEngineService.resolve(spec.getEngine());
            if (engineOpt.isEmpty()) {
                sender.sendError(wsSession, envelope, 404,
                        "Unknown think-engine '" + spec.getEngine()
                                + "' — registered: " + thinkEngineService.listEngines());
                return;
            }
            ThinkEngine engine = engineOpt.get();

            ThinkProcessDocument fresh;
            try {
                fresh = thinkProcessService.create(
                        session.getTenantId(),
                        session.getSessionId(),
                        spec.getName(),
                        engine.name(),
                        engine.version(),
                        spec.getTitle(),
                        spec.getGoal());
            } catch (ThinkProcessService.ThinkProcessAlreadyExistsException e) {
                // Extremely rare race with a concurrent bootstrap — treat as skip.
                Optional<ThinkProcessDocument> raced = thinkProcessService
                        .findByName(session.getTenantId(), session.getSessionId(), spec.getName());
                raced.ifPresent(d -> skipped.add(toBootstrapped(d)));
                continue;
            }

            try {
                thinkEngineService.start(fresh);
            } catch (RuntimeException e) {
                log.error("Engine start failed for process id='{}' engine='{}'",
                        fresh.getId(), engine.name(), e);
                sender.sendError(wsSession, envelope, 500,
                        "Engine start failed for '" + spec.getName() + "': " + e.getMessage());
                return;
            }

            // Push every message the engine produced during start() — typically a greeting.
            pushAppendedMessages(wsSession, fresh, spec.getName(), 0);

            ThinkProcessDocument refreshed = thinkProcessService.findById(fresh.getId()).orElse(fresh);
            created.add(toBootstrapped(refreshed));
            if (firstProcess == null) {
                firstProcess = refreshed;
            }
        }

        // ── Optional initial steer ───────────────────────────────────────
        String steeredProcessName = null;
        if (!isBlank(request.getInitialMessage()) && firstProcess != null) {
            int beforeSize = chatMessageService.history(
                    firstProcess.getTenantId(),
                    firstProcess.getSessionId(),
                    firstProcess.getId()).size();
            SteerMessage.UserChatInput userInput = new SteerMessage.UserChatInput(
                    Instant.now(), null, ctx.getUserId(), request.getInitialMessage());
            try {
                thinkEngineService.steer(firstProcess, userInput);
                pushAppendedMessages(wsSession, firstProcess, firstProcess.getName(), beforeSize);
                steeredProcessName = firstProcess.getName();
            } catch (RuntimeException e) {
                log.error("Initial steer failed for process id='{}'", firstProcess.getId(), e);
                sender.sendError(wsSession, envelope, 500,
                        "Initial steer failed: " + e.getMessage());
                return;
            }
        }

        SessionBootstrapResponse response = SessionBootstrapResponse.builder()
                .sessionId(session.getSessionId())
                .projectId(session.getProjectId())
                .sessionCreated(sessionCreated)
                .processesCreated(created)
                .processesSkipped(skipped)
                .steeredProcessName(steeredProcessName)
                .build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_BOOTSTRAP, response);
    }

    // ──────────────────── Session sub-steps ────────────────────

    /**
     * Walks the user's OPEN sessions, newest-activity first, and tries to
     * atomically bind each one to this connection. First successful bind
     * wins. Returns {@link Optional#empty()} if the user has no unbound
     * resumable session — the caller will fall through to session creation.
     */
    private Optional<SessionDocument> tryAutoResumeLatest(ConnectionContext ctx) {
        List<SessionDocument> candidates = sessionService
                .listForUser(ctx.getTenantId(), ctx.getUserId()).stream()
                .filter(s -> s.getStatus() == SessionStatus.OPEN)
                .filter(s -> s.getBoundConnectionId() == null)
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
                    candidate.getSessionId(), ctx.getConnectionId(), ctx.getPodIp())) {
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
            // Default: pick the first project in the tenant — CLI callers
            // can drop the bootstrap block entirely and still get a
            // working session.
            List<de.mhus.vance.shared.project.ProjectDocument> all =
                    projectService.all(ctx.getTenantId());
            if (all.isEmpty()) {
                sender.sendError(wsSession, envelope, 404,
                        "No projects found in tenant '" + ctx.getTenantId() + "'");
                return Optional.empty();
            }
            projectId = all.get(0).getName();
            log.info("session-bootstrap: defaulted projectId to '{}' (tenant='{}')",
                    projectId, ctx.getTenantId());
        } else if (projectService.findByTenantAndName(ctx.getTenantId(), projectId).isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Project '" + projectId + "' not found");
            return Optional.empty();
        }
        SessionDocument fresh = sessionService.create(
                ctx.getTenantId(),
                ctx.getUserId(),
                projectId,
                ctx.getDisplayName(),
                ctx.getClientType(),
                ctx.getClientVersion());
        boolean bound = sessionService.tryBind(
                fresh.getSessionId(), ctx.getConnectionId(), ctx.getPodIp());
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
        if (existing.isEmpty() || existing.get().getStatus() != SessionStatus.OPEN) {
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
        boolean bound = sessionService.tryBind(
                doc.getSessionId(), ctx.getConnectionId(), ctx.getPodIp());
        if (!bound) {
            sender.sendError(wsSession, envelope, 409,
                    "Session '" + doc.getSessionId() + "' is already bound to another connection");
            return Optional.empty();
        }
        return Optional.of(doc);
    }

    // ──────────────────── Notification push ────────────────────

    private void pushAppendedMessages(
            WebSocketSession wsSession, ThinkProcessDocument process, String processName, int beforeSize)
            throws IOException {
        List<ChatMessageDocument> full = chatMessageService.history(
                process.getTenantId(), process.getSessionId(), process.getId());
        for (ChatMessageDocument appended : full.subList(beforeSize, full.size())) {
            sender.sendNotification(wsSession, MessageType.CHAT_MESSAGE_APPENDED,
                    toDto(appended, processName));
        }
    }

    private static ChatMessageAppendedData toDto(ChatMessageDocument doc, String processName) {
        return ChatMessageAppendedData.builder()
                .chatMessageId(doc.getId())
                .thinkProcessId(doc.getThinkProcessId())
                .processName(processName)
                .role(doc.getRole())
                .content(doc.getContent())
                .createdAt(doc.getCreatedAt())
                .build();
    }

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
}
