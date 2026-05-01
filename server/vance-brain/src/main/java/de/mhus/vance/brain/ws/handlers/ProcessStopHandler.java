package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.thinkprocess.ProcessStopRequest;
import de.mhus.vance.api.thinkprocess.ProcessStopResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * User-driven stop. Two modes, decided by {@link ProcessStopRequest#getProcessName()}:
 *
 * <ul>
 *   <li><b>Blank/null</b> — stop every non-CLOSED child of the
 *       session's chat-process. Symmetric counterpart to the
 *       {@code process-pause} broadcast — same target set, but
 *       transitions to {@code CLOSED} (with {@code closeReason=STOPPED})
 *       instead of {@code PAUSED}. The chat-process itself is
 *       untouched. Used by the foot {@code /stop} command — "abandon
 *       this direction".</li>
 *   <li><b>Set</b> — stop that single process by name. Symmetric to
 *       the orchestrator-only {@code process_stop} brain-tool but
 *       reachable from the user.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessStopHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final SessionLifecycleService sessionLifecycle;
    private final ProgressEmitter progressEmitter;
    private final RequestAuthority authority;

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
        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }
        authority.enforce(ctx,
                new Resource.Session(tenantId,
                        ctx.getProjectId() == null ? "" : ctx.getProjectId(), sessionId),
                Action.EXECUTE);

        String processName = request == null ? null : request.getProcessName();
        if (processName == null || processName.isBlank()) {
            // Same "halt requested" feedback as the pause path —
            // mid-turn engines reach the next safe boundary first.
            emitHaltRequestedForActiveWorkers(tenantId, sessionId);
            // Broadcast: stop active workers under the chat-process.
            List<String> stopped = sessionLifecycle.stopChildrenOfChat(sessionId);
            log.info("process-stop sessionId='{}' stopped={}", sessionId, stopped);
            ProcessStopResponse response = ProcessStopResponse.builder()
                    .stoppedProcessNames(stopped)
                    .build();
            sender.sendReply(wsSession, envelope, MessageType.PROCESS_STOP, response);
            return;
        }

        // Single named process — orchestrator-tool style.
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findByName(tenantId, sessionId, processName);
        if (processOpt.isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Think-process '" + processName + "' not found in session '"
                            + sessionId + "'");
            return;
        }
        ThinkProcessDocument process = processOpt.get();

        if (process.getStatus() != ThinkProcessStatus.CLOSED) {
            progressEmitter.emitStatus(process, StatusTag.ENGINE_HALT_REQUESTED,
                    process.getName() + " stop requested");
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
                .stoppedProcessNames(List.of(refreshed.getName()))
                .status(refreshed.getStatus())
                .closeReason(refreshed.getCloseReason())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_STOP, response);
    }

    /** Same logic as in {@code ProcessPauseHandler} — kept inline to avoid coupling. */
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
            if (s == ThinkProcessStatus.CLOSED) continue;
            progressEmitter.emitStatus(p, StatusTag.ENGINE_HALT_REQUESTED,
                    p.getName() + " stop requested");
        }
    }
}
