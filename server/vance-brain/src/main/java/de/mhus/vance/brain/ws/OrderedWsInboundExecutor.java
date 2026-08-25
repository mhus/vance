package de.mhus.vance.brain.ws;

import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Production {@link WsInboundExecutor}: one FIFO inbox per connection,
 * drained by a virtual thread.
 *
 * <p>Same shape as {@link de.mhus.vance.brain.scheduling.LaneScheduler} — and
 * deliberately not the same object. A lane is keyed on a think-process and
 * lives as long as that process does; an inbox is keyed on a socket, must be
 * bounded (a client can push frames faster than handlers retire them) and
 * must be <em>discarded</em> on close rather than drained. Sharing the lane
 * map would mean either giving lanes a queue limit they must not have, or
 * leaving this one unbounded.
 *
 * <p>Backpressure is explicit: past
 * {@link VanceBrainProperties#getInboundQueueLimit()} pending frames the
 * connection is closed with {@code 1013 Service Overload}. Silently dropping
 * frames is worse than closing — a client whose {@code process-steer} vanished
 * without a trace has no way to notice, whereas a closed socket triggers its
 * reconnect path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderedWsInboundExecutor implements WsInboundExecutor {

    private final VanceBrainProperties properties;

    /** Pending frame work, keyed by {@code WebSocketSession.getId()}. */
    private final Map<String, Inbox> inboxes = new ConcurrentHashMap<>();

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void submit(WebSocketSession wsSession, FrameWork work) {
        String id = wsSession.getId();
        Inbox inbox = inboxes.computeIfAbsent(id, key -> new Inbox());
        int limit = Math.max(1, properties.getInboundQueueLimit());
        boolean startDrain;
        boolean overflow;
        synchronized (inbox) {
            if (inbox.discarded) {
                // Socket already closed between the container's read and here.
                return;
            }
            overflow = inbox.queue.size() >= limit;
            if (!overflow) {
                inbox.queue.add(work);
            }
            startDrain = !overflow && !inbox.draining;
            if (startDrain) {
                inbox.draining = true;
            }
        }
        // Outside the monitor: closing a socket takes the connection's write
        // lock, and holding the inbox monitor across that would let a stuck
        // write block every submit on this connection.
        if (overflow) {
            log.warn("WebSocket {} exceeded the inbound queue limit ({}) — closing.", id, limit);
            forget(id);
            close(wsSession, CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        if (startDrain) {
            workers.execute(() -> drain(wsSession, inbox));
        }
    }

    @Override
    public void forget(String wsSessionId) {
        Inbox inbox = inboxes.remove(wsSessionId);
        if (inbox == null) return;
        int dropped;
        synchronized (inbox) {
            inbox.discarded = true;
            dropped = inbox.queue.size();
            inbox.queue.clear();
        }
        if (dropped > 0) {
            log.debug("Dropped {} unprocessed inbound frame(s) of closed WebSocket {}.",
                    dropped, wsSessionId);
        }
    }

    /** Number of connections with a live inbox. Package-private for tests. */
    int trackedConnections() {
        return inboxes.size();
    }

    private void drain(WebSocketSession wsSession, Inbox inbox) {
        while (true) {
            @Nullable FrameWork next;
            synchronized (inbox) {
                next = inbox.discarded ? null : inbox.queue.poll();
                if (next == null) {
                    inbox.draining = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (Throwable t) {
                // What ExceptionWebSocketHandlerDecorator does for a handler
                // that throws on the read thread — kept identical here so
                // moving the work off that thread does not quietly turn a
                // fatal frame into a connection that limps on.
                log.warn("Inbound frame handling failed on WebSocket {} — closing: {}",
                        wsSession.getId(), t.toString(), t);
                forget(wsSession.getId());
                close(wsSession, CloseStatus.SERVER_ERROR);
                return;
            }
        }
    }

    private static void close(WebSocketSession wsSession, CloseStatus status) {
        try {
            wsSession.close(status);
        } catch (Exception e) {
            log.trace("Close of WebSocket {} failed (ignored): {}",
                    wsSession.getId(), e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    /** One connection's pending frames plus its drain state. */
    private static final class Inbox {
        private final ArrayDeque<FrameWork> queue = new ArrayDeque<>();
        /** A drain loop is running; further submits only enqueue. */
        private boolean draining;
        /** The connection is gone — enqueue and drain both stop. */
        private boolean discarded;
    }
}
