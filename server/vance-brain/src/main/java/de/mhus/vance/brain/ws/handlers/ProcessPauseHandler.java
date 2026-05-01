package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.thinkprocess.ProcessPauseRequest;
import de.mhus.vance.api.thinkprocess.ProcessPauseResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * User-driven pause. Two modes, decided by {@link ProcessPauseRequest#getProcessName()}:
 *
 * <ul>
 *   <li><b>Blank/null</b> — pause every non-CLOSED <em>child</em> of the
 *       session's chat-process. The "user pressed ESC, wants to halt
 *       worker(s) and redirect" path. Chat-process itself is untouched.</li>
 *   <li><b>Set</b> — pause that single process, by name within the session.</li>
 * </ul>
 *
 * <p>Resume happens via the orchestrator (Arthur calls
 * {@code process_resume} after deciding what to do with the new info)
 * or via the symmetric {@code process-resume} WS handler.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessPauseHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final SessionLifecycleService sessionLifecycle;
    private final LaneScheduler laneScheduler;
    private final ProgressEmitter progressEmitter;

    @Override
    public String type() {
        return MessageType.PROCESS_PAUSE;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessPauseRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessPauseRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid process-pause payload: " + e.getMessage());
            return;
        }

        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }

        String processName = request == null ? null : request.getProcessName();
        List<String> paused;
        if (processName == null || processName.isBlank()) {
            // Emit a "halt requested" ping for each candidate child
            // immediately so the user sees feedback even if the
            // engine is still mid-turn (the actual ENGINE_PAUSED
            // ping fires once the lane reaches the next boundary).
            emitHaltRequestedForActiveWorkers(tenantId, sessionId);
            paused = sessionLifecycle.pauseChildrenOfChat(sessionId);
        } else {
            // Single named process.
            Optional<ThinkProcessDocument> processOpt =
                    thinkProcessService.findByName(tenantId, sessionId, processName);
            if (processOpt.isEmpty()) {
                sender.sendError(wsSession, envelope, 404,
                        "Think-process '" + processName + "' not found in session");
                return;
            }
            ThinkProcessDocument target = processOpt.get();
            ThinkProcessStatus s = target.getStatus();
            if (s == ThinkProcessStatus.CLOSED || s == ThinkProcessStatus.PAUSED) {
                paused = List.of();
            } else {
                progressEmitter.emitStatus(target, StatusTag.ENGINE_HALT_REQUESTED,
                        target.getName() + " pause requested");
                try {
                    laneScheduler.submit(target.getId(), () -> {
                        thinkProcessService.updateStatus(target.getId(), ThinkProcessStatus.PAUSED);
                        return null;
                    }).get();
                    paused = List.of(target.getName());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    sender.sendError(wsSession, envelope, 500,
                            "Interrupted while pausing");
                    return;
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    sender.sendError(wsSession, envelope, 500,
                            "Pause failed: " + cause.getMessage());
                    return;
                }
            }
        }

        log.info("process-pause sessionId='{}' paused={}", sessionId, paused);
        ProcessPauseResponse response = ProcessPauseResponse.builder()
                .pausedProcessNames(paused)
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_PAUSE, response);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /**
     * Walks the chat-process's children and emits an
     * ENGINE_HALT_REQUESTED ping per active worker. Decoupled from
     * the actual pause path so the user gets immediate "I heard
     * you" feedback even when the lane queue is busy.
     */
    private void emitHaltRequestedForActiveWorkers(String tenantId, String sessionId) {
        java.util.List<ThinkProcessDocument> all =
                thinkProcessService.findBySession(tenantId, sessionId);
        ThinkProcessDocument chat = all.stream()
                .filter(p -> p.getParentProcessId() == null)
                .filter(p -> "chat".equals(p.getName()))
                .findFirst()
                .orElse(null);
        if (chat == null) return;
        for (ThinkProcessDocument p : all) {
            if (!chat.getId().equals(p.getParentProcessId())) continue;
            ThinkProcessStatus s = p.getStatus();
            if (s == ThinkProcessStatus.CLOSED || s == ThinkProcessStatus.PAUSED) {
                continue;
            }
            progressEmitter.emitStatus(p, StatusTag.ENGINE_HALT_REQUESTED,
                    p.getName() + " pause requested");
        }
    }
}
