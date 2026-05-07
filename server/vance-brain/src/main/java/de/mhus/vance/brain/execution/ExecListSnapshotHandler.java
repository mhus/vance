package de.mhus.vance.brain.execution;

import de.mhus.vance.api.execution.ExecEvent;
import de.mhus.vance.api.execution.ExecListSnapshot;
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
 * Inbound handler for {@link MessageType#EXEC_LIST_SNAPSHOT}: foot
 * sends the full list of currently known jobs at connect / bind time.
 * Brain drops every prior entry owned by this connection and replaces
 * them with the snapshot — the canonical reconciliation point.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecListSnapshotHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ExecutionRegistryService registry;

    @Override
    public String type() {
        return MessageType.EXEC_LIST_SNAPSHOT;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return true;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ExecListSnapshot snapshot;
        try {
            snapshot = objectMapper.convertValue(envelope.getData(), ExecListSnapshot.class);
        } catch (RuntimeException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid exec-list-snapshot payload: " + e.getMessage());
            return;
        }
        ExecutionOwner owner = new ExecutionOwner.Foot(ctx.getConnectionId());

        int dropped = registry.removeByFootClient(ctx.getConnectionId());
        log.debug("exec-list-snapshot: dropped {} stale entries for client '{}'",
                dropped, ctx.getConnectionId());

        if (snapshot == null || snapshot.getExecutions() == null) return;
        for (ExecEvent e : snapshot.getExecutions()) {
            if (e.getExecutionId() == null || e.getExecutionId().isBlank()) continue;
            registry.register(new ExecutionRegistryEntry(
                    e.getExecutionId(),
                    owner,
                    ctx.getTenantId(),
                    e.getProjectId() != null ? e.getProjectId() : ctx.getProjectId(),
                    e.getSessionId() != null ? e.getSessionId() : ctx.getSessionId(),
                    null,
                    e.getCommand() != null ? e.getCommand() : "",
                    null,
                    e.getStartedAt() != null ? e.getStartedAt() : Instant.now(),
                    e.getLastOutputAt() != null
                            ? e.getLastOutputAt()
                            : e.getStartedAt() != null ? e.getStartedAt() : Instant.now(),
                    e.getEndedAt(),
                    ExecEventHandler.parseStatus(e.getStatus(), ExecutionStatus.RUNNING),
                    e.getExitCode(),
                    e.getStdoutPath(),
                    e.getStderrPath()));
        }
    }
}
