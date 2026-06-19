package de.mhus.vance.brain.execution;

import de.mhus.vance.api.execution.ExecEvent;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    /**
     * Lazy: the router reaches every {@code Tool} bean transitively
     * through the engine, and several of those tools (e.g.
     * {@code client_exec_run}) end up calling this handler — direct
     * injection would close the cycle.
     */
    private final ObjectProvider<EngineMessageRouter> engineMessageRouter;

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
        ExecutionOwner owner = new ExecutionOwner.Foot(ctx.getEditorId());
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
            case ENDED -> {
                registry.updateProgress(
                        event.getExecutionId(),
                        event.getLastOutputAt() != null ? event.getLastOutputAt() : Instant.now(),
                        parseStatus(event.getStatus(), ExecutionStatus.COMPLETED),
                        event.getExitCode(),
                        event.getEndedAt() != null ? event.getEndedAt() : Instant.now());
                pushOwnerCompletionIfTracked(event);
            }
        }
    }

    /**
     * Mirror of {@code ExecManager.pushCompletionIfTracked} for
     * foot-spawned jobs: when the registry entry carries an owner
     * processId (attached at invoke-time by
     * {@code ClientToolSource}), dispatch {@code EXEC_FINISHED} or
     * {@code EXEC_TIMEOUT} into the owner's inbox so the engine can
     * react without polling.
     *
     * <p>Tail output stays on the foot host — the LLM can pull it via
     * {@code client_exec_tail} on demand. Including it on the frame
     * would inflate every ENDED push.
     */
    private void pushOwnerCompletionIfTracked(ExecEvent event) {
        ExecutionRegistryEntry entry = registry.find(event.getExecutionId()).orElse(null);
        if (entry == null || entry.processId() == null || entry.processId().isBlank()) {
            return;
        }
        String ownerProcessId = entry.processId();
        boolean timedOut = Boolean.TRUE.equals(event.getTimedOut());
        ProcessEventType eventType = timedOut
                ? ProcessEventType.EXEC_TIMEOUT
                : ProcessEventType.EXEC_FINISHED;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jobId", event.getExecutionId());
            String status = event.getStatus() != null ? event.getStatus() : "";
            payload.put("status", status);
            if (event.getExitCode() != null) {
                payload.put("exitCode", event.getExitCode());
            }
            Instant ended = event.getEndedAt();
            if (ended != null) {
                payload.put("finishedAt", ended.toString());
            }
            if (entry.projectId() != null) {
                payload.put("projectId", entry.projectId());
            }
            payload.put("source", "foot");
            if (timedOut) {
                long runMs = Duration.between(
                        entry.startedAt(), ended != null ? ended : Instant.now()).toMillis();
                payload.put("killedAfterSeconds", runMs / 1000);
            }
            String summary = timedOut
                    ? "Client exec " + event.getExecutionId() + " timed out"
                    : "Client exec " + event.getExecutionId() + " "
                            + (status.isBlank() ? "ended" : status.toLowerCase());
            PendingMessageDocument doc = PendingMessageDocument.builder()
                    .type(PendingMessageType.PROCESS_EVENT)
                    .at(Instant.now())
                    .sourceProcessId(ownerProcessId)
                    .eventType(eventType)
                    .content(summary)
                    .payload(payload)
                    .eventId(java.util.UUID.randomUUID().toString())
                    .build();
            boolean ok = engineMessageRouter.getObject()
                    .dispatch(ownerProcessId, ownerProcessId, doc);
            if (!ok) {
                log.warn("Foot {} dispatch dropped owner='{}' exec='{}'",
                        eventType, ownerProcessId, event.getExecutionId());
            }
        } catch (RuntimeException e) {
            log.warn("Foot {} dispatch failed owner='{}' exec='{}': {}",
                    eventType, ownerProcessId, event.getExecutionId(), e.toString(), e);
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
