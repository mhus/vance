package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks client-registered tools per session, plus the pending
 * invocations awaiting a {@code client-tool-result}.
 *
 * <p>Only valid inside the pod that holds the session's bind — client
 * tools are routed over that one WebSocket. When the session unbinds
 * (graceful close or startup cleanup), {@link #unregister} is called
 * and any pending futures fail fast with a clear message.
 *
 * <p>This is a skeleton: it stores per-session state in memory and
 * assumes a single connection per session. Sharing across pods would
 * need a pub-sub layer; that hasn't been designed yet.
 */
@Component
@Slf4j
public class ClientToolRegistry {

    private final Map<String, Entry> bySession = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong correlationSeq = new AtomicLong();

    /** Overwrites any prior registration for {@code sessionId}. */
    public void register(
            String sessionId,
            String connectionId,
            WebSocketSession wsSession,
            List<ToolSpec> tools) {
        Map<String, ToolSpec> byName = new java.util.LinkedHashMap<>();
        for (ToolSpec t : tools) {
            byName.put(t.getName(), t);
        }
        bySession.put(sessionId, new Entry(connectionId, wsSession, Map.copyOf(byName)));
        log.info("ClientToolRegistry session='{}' registered {} tools: {}",
                sessionId, byName.size(), byName.keySet());
    }

    /**
     * Removes a session's registration and fails any pending invocations
     * that were waiting for a reply over that connection.
     */
    public void unregister(String sessionId) {
        Entry removed = bySession.remove(sessionId);
        if (removed == null) return;
        log.info("ClientToolRegistry session='{}' unregistered", sessionId);
        pending.values().removeIf(p -> {
            if (p.sessionId.equals(sessionId)) {
                p.future.completeExceptionally(
                        new IllegalStateException(
                                "Client disconnected before answering tool '"
                                        + p.toolName + "'"));
                return true;
            }
            return false;
        });
    }

    /** Tools registered for this session, empty if none. */
    public List<ToolSpec> toolsFor(String sessionId) {
        Entry e = bySession.get(sessionId);
        return e == null ? List.of() : List.copyOf(e.tools.values());
    }

    /** Look up one tool spec by name within a session. */
    public Optional<ToolSpec> find(String sessionId, String name) {
        Entry e = bySession.get(sessionId);
        if (e == null) return Optional.empty();
        return Optional.ofNullable(e.tools.get(name));
    }

    /** Routing info for invocation — caller writes to the WebSocket. */
    public Optional<Entry> entry(String sessionId) {
        return Optional.ofNullable(bySession.get(sessionId));
    }

    /** Allocates a new correlation id and a future to wait on. */
    public Pending beginInvocation(String sessionId, String toolName) {
        String id = "ct-" + correlationSeq.incrementAndGet();
        Pending p = new Pending(id, sessionId, toolName, new CompletableFuture<>());
        pending.put(id, p);
        return p;
    }

    /** Matches an incoming result to a pending future. */
    public Optional<Pending> completeInvocation(
            String correlationId,
            @Nullable Map<String, Object> result,
            @Nullable String error) {
        Pending p = pending.remove(correlationId);
        if (p == null) return Optional.empty();
        if (error != null) {
            p.future.completeExceptionally(new ClientToolFailureException(error));
        } else {
            p.future.complete(result == null ? Map.of() : result);
        }
        return Optional.of(p);
    }

    /** Cancels a pending invocation — used on timeout. */
    public void cancel(String correlationId, String reason) {
        Pending p = pending.remove(correlationId);
        if (p != null) {
            p.future.completeExceptionally(new ClientToolFailureException(reason));
        }
    }

    /** Per-session routing data. Package-private so the source can read it. */
    public record Entry(
            String connectionId,
            WebSocketSession wsSession,
            Map<String, ToolSpec> tools) {}

    /** A tool invocation awaiting a reply. */
    public record Pending(
            String correlationId,
            String sessionId,
            String toolName,
            CompletableFuture<Map<String, Object>> future) {}
}
