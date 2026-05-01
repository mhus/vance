package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.thinkprocess.ProcessResumeRequest;
import de.mhus.vance.api.thinkprocess.ProcessResumeResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Symmetric counterpart to {@link ProcessPauseHandler}. Mostly for
 * tests and admin UIs — the regular resume path goes through the
 * orchestrator (Arthur using the {@code process_resume} brain-tool).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessResumeHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final SessionLifecycleService sessionLifecycle;
    private final ProcessEventEmitter processEventEmitter;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.PROCESS_RESUME;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessResumeRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessResumeRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid process-resume payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getProcessName())) {
            sender.sendError(wsSession, envelope, 400, "processName is required");
            return;
        }
        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findByName(tenantId, sessionId, request.getProcessName());
        if (processOpt.isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Think-process '" + request.getProcessName() + "' not found in session");
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        authority.enforce(ctx,
                new Resource.ThinkProcess(process.getTenantId(), process.getProjectId(),
                        process.getSessionId(), process.getId() == null ? "" : process.getId()),
                Action.EXECUTE);
        try {
            sessionLifecycle.resumeProcess(process, processEventEmitter);
        } catch (RuntimeException e) {
            log.warn("process-resume failed id='{}': {}", process.getId(), e.toString());
            sender.sendError(wsSession, envelope, 500,
                    "Resume failed: " + e.getMessage());
            return;
        }
        ThinkProcessDocument refreshed = thinkProcessService.findById(process.getId())
                .orElse(process);
        ProcessResumeResponse response = ProcessResumeResponse.builder()
                .processName(refreshed.getName())
                .status(refreshed.getStatus())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_RESUME, response);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
