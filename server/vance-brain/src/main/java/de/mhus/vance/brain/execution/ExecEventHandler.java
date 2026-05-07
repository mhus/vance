package de.mhus.vance.brain.execution;

import de.mhus.vance.api.execution.ExecEvent;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import java.io.IOException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound handler for {@link MessageType#EXEC_EVENT}: foot pushes a
 * single shell-job life-cycle event. Updates the brain-side
 * cross-side execution registry. Pre-session events are accepted —
 * the foot may have started a job before binding a session.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecEventHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ExecutionRegistryService registry;

    @Override
    public String type() {
        return MessageType.EXEC_EVENT;
    }

    /** Pre-session events are valid — jobs can outlive a bind. */
    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return true;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ExecEvent event;
        try {
            event = objectMapper.convertValue(envelope.getData(), ExecEvent.class);
        } catch (RuntimeException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid exec-event payload: " + e.getMessage());
            return;
        }
        ExecEvent.Kind kind = event == null ? null : event.kindOrNull();
        if (event == null || kind == null
                || event.getExecutionId() == null || event.getExecutionId().isBlank()) {
            sender.sendError(wsSession, envelope, 400,
                    "exec-event requires kind and executionId");
            return;
        }
        applyEvent(ctx, kind, event);
    }

    private void applyEvent(ConnectionContext ctx, ExecEvent.Kind kind, ExecEvent event) {
        ExecutionOwner owner = new ExecutionOwner.Foot(ctx.getConnectionId());
        switch (kind) {
            case STARTED -> registry.register(new ExecutionRegistryEntry(
                    event.getExecutionId(),
                    owner,
                    ctx.getTenantId(),
                    event.getProjectId() != null ? event.getProjectId() : ctx.getProjectId(),
                    event.getSessionId() != null ? event.getSessionId() : ctx.getSessionId(),
                    null,
                    event.getCommand() != null ? event.getCommand() : "",
                    null,
                    event.getStartedAt() != null ? event.getStartedAt() : Instant.now(),
                    event.getLastOutputAt() != null
                            ? event.getLastOutputAt()
                            : event.getStartedAt() != null ? event.getStartedAt() : Instant.now(),
                    null,
                    parseStatus(event.getStatus(), ExecutionStatus.RUNNING),
                    event.getExitCode(),
                    event.getStdoutPath(),
                    event.getStderrPath()));
            case TICK -> registry.find(event.getExecutionId()).ifPresent(entry ->
                    registry.updateProgress(
                            entry.executionId(),
                            event.getLastOutputAt() != null ? event.getLastOutputAt() : entry.lastOutputAt(),
                            entry.status(),
                            entry.exitCode(),
                            entry.endedAt()));
            case ENDED -> registry.updateProgress(
                    event.getExecutionId(),
                    event.getLastOutputAt() != null ? event.getLastOutputAt() : Instant.now(),
                    parseStatus(event.getStatus(), ExecutionStatus.COMPLETED),
                    event.getExitCode(),
                    event.getEndedAt() != null ? event.getEndedAt() : Instant.now());
        }
    }

    static ExecutionStatus parseStatus(String raw, ExecutionStatus fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return ExecutionStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
