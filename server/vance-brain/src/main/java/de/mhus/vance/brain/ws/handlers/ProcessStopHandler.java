package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.thinkprocess.ProcessStopRequest;
import de.mhus.vance.api.thinkprocess.ProcessStopResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
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
 * User-driven WS counterpart to the orchestrator's {@code process_stop}
 * brain-tool. Runs the engine's {@code stop} on the target process's lane,
 * transitioning the process to {@code CLOSED} with {@code closeReason=STOPPED}.
 *
 * <p>This is the handler the foot-client wires to ESC: when the user
 * presses ESC during a running chat-process, foot sends a
 * {@code process-stop} for the chat-process name and the engine halts
 * at the next safe boundary (Tool grenze, Step-Grenze, or LLM call end).
 *
 * <p>Idempotent — stopping an already-CLOSED process returns its current
 * shape without re-running the engine hook.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessStopHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final SessionLifecycleService sessionLifecycle;

    @Override
    public String type() {
        return MessageType.PROCESS_STOP;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessStopRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessStopRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid process-stop payload: " + e.getMessage());
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
                    "Think-process '" + request.getProcessName() + "' not found in session '"
                            + sessionId + "'");
            return;
        }
        ThinkProcessDocument process = processOpt.get();

        // Already terminal — short-circuit, no engine call.
        if (process.getStatus() != ThinkProcessStatus.CLOSED) {
            try {
                sessionLifecycle.stopProcess(process);
            } catch (RuntimeException e) {
                log.warn("process-stop failed id='{}': {}", process.getId(), e.toString());
                sender.sendError(wsSession, envelope, 500,
                        "Engine stop failed: " + e.getMessage());
                return;
            }
        }

        ThinkProcessDocument refreshed = thinkProcessService.findById(process.getId())
                .orElse(process);
        ProcessStopResponse response = ProcessStopResponse.builder()
                .processName(refreshed.getName())
                .status(refreshed.getStatus())
                .closeReason(refreshed.getCloseReason())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_STOP, response);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
