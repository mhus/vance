package de.mhus.vance.brain.ws;

import org.springframework.web.socket.WebSocketSession;

/**
 * Where the work of an inbound WebSocket frame runs.
 *
 * <p>A servlet container reads one connection on one thread: the next frame
 * is not read until the handler for the previous one returns. Anything a
 * handler waits for therefore stalls <em>the whole connection</em> — not just
 * that one request. The measured consequence: a {@code process-pause} that
 * waited 70s for a busy engine lane also held back the browser's PONG frames,
 * {@link WebSocketKeepAliveService} saw a silent socket and evicted it, and
 * the message the user typed in the meantime was still sitting unread in the
 * socket buffer when it was dropped. From the outside that reads as "the
 * connection died and ate my input".
 *
 * <p>This seam takes frame work off the read thread so no handler — present
 * or future — can do that again. It is deliberately <em>not</em> a plain
 * thread pool: frames of one connection must keep their order (a
 * {@code session-resume} has to bind before the {@code process-steer} behind
 * it is dispatched), so execution is serial per connection and parallel
 * across connections.
 *
 * <p>Note what this does and does not buy. Liveness is restored
 * unconditionally: pongs are handled on the read thread and are never queued
 * behind application work. Latency is not — a slow handler still delays the
 * frames of its own connection, which is the price of ordering. The cure for
 * that is a handler that does not wait (see
 * {@link de.mhus.vance.brain.scheduling.LaneScheduler} and
 * {@code SessionLifecycleService#pauseActiveInSession}).
 */
public interface WsInboundExecutor {

    /**
     * The work of a single frame. Allowed to throw: the executor logs it and
     * closes the connection, which is what the container's
     * {@code ExceptionWebSocketHandlerDecorator} would have done had the work
     * still run on the read thread.
     */
    @FunctionalInterface
    interface FrameWork {
        void run() throws Exception;
    }

    /**
     * Queues {@code work} for {@code wsSession} and returns immediately.
     * Frames of one session run in submission order.
     */
    void submit(WebSocketSession wsSession, FrameWork work);

    /**
     * Drops everything still queued for a connection that is gone. Called
     * from {@code afterConnectionClosed}: frames addressed to a closed socket
     * can no longer be answered, so running them would only produce failed
     * sends and side effects the client will never learn about.
     */
    void forget(String wsSessionId);
}
