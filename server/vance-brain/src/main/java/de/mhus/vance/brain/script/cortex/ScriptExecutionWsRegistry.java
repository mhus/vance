package de.mhus.vance.brain.script.cortex;

import de.mhus.vance.api.scripts.ScriptExecutionEventData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * In-memory map from {@code executionId} to the WebSocket session that
 * wants the {@code script-execution-*} push frames. Registered by the
 * {@code script-execution-subscribe} WS handler; cleaned up after the
 * execution finishes or when the WebSocket closes.
 *
 * <p>Single-pod scope. No cross-pod routing — Script Cortex executions
 * are always served by the pod that accepted the {@code POST /execute}
 * call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScriptExecutionWsRegistry {

    private final WebSocketSender sender;

    private final Map<String, WebSocketSession> byExecution = new ConcurrentHashMap<>();

    /** Bind {@code executionId} to {@code wsSession} — overwrites any
     *  previous binding (last subscriber wins, typically the same UI
     *  tab refreshing). */
    public void register(String executionId, WebSocketSession wsSession) {
        byExecution.put(executionId, wsSession);
        log.debug("ScriptExecutionWsRegistry bound execution='{}' to ws='{}'",
                executionId, wsSession.getId());
    }

    /** Drop the binding for {@code executionId}. Idempotent. */
    public void unregister(String executionId) {
        WebSocketSession removed = byExecution.remove(executionId);
        if (removed != null) {
            log.debug("ScriptExecutionWsRegistry released execution='{}'", executionId);
        }
    }

    /** Drop every binding pointing at {@code wsSession}. Called on
     *  WebSocket close so dead sessions don't leak. */
    public void unregisterAllFor(WebSocketSession wsSession) {
        byExecution.entrySet().removeIf(e -> e.getValue() == wsSession);
    }

    /**
     * Push one envelope to the registered subscriber, if any. Silent
     * no-op when nobody is listening — the brain still runs the
     * execution, the UI just won't see live frames (it can poll
     * {@code GET /executions/{id}}).
     */
    public void push(String executionId, String type, ScriptExecutionEventData data) {
        WebSocketSession ws = byExecution.get(executionId);
        if (ws == null) return;
        if (!ws.isOpen()) {
            byExecution.remove(executionId, ws);
            return;
        }
        try {
            sender.sendNotification(ws, type, data);
        } catch (IOException e) {
            log.warn("Failed to push '{}' for execution='{}': {}",
                    type, executionId, e.toString());
        }
    }

    /** Convenience wrappers for the four lifecycle stages. */
    public void pushStarted(String executionId, long startedAtMs) {
        push(executionId, MessageType.SCRIPT_EXECUTION_STARTED,
                ScriptExecutionEventData.builder()
                        .executionId(executionId)
                        .startedAtMs(startedAtMs)
                        .build());
    }

    public void pushLog(String executionId, String stream, String line) {
        push(executionId, MessageType.SCRIPT_EXECUTION_LOG,
                ScriptExecutionEventData.builder()
                        .executionId(executionId)
                        .stream(stream)
                        .logLine(line)
                        .build());
    }

    public void pushFinished(
            String executionId,
            @Nullable Object resultValue,
            long endedAtMs,
            long durationMs) {
        push(executionId, MessageType.SCRIPT_EXECUTION_FINISHED,
                ScriptExecutionEventData.builder()
                        .executionId(executionId)
                        .resultValue(resultValue)
                        .endedAtMs(endedAtMs)
                        .durationMs(durationMs)
                        .build());
    }

    public void pushFailed(
            String executionId,
            String errorMessage,
            long endedAtMs,
            long durationMs) {
        push(executionId, MessageType.SCRIPT_EXECUTION_FAILED,
                ScriptExecutionEventData.builder()
                        .executionId(executionId)
                        .errorMessage(errorMessage)
                        .endedAtMs(endedAtMs)
                        .durationMs(durationMs)
                        .build());
    }

    public void pushCancelled(String executionId, long endedAtMs, long durationMs) {
        push(executionId, MessageType.SCRIPT_EXECUTION_CANCELLED,
                ScriptExecutionEventData.builder()
                        .executionId(executionId)
                        .endedAtMs(endedAtMs)
                        .durationMs(durationMs)
                        .build());
    }
}
