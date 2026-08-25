package de.mhus.vance.brain.ws;

import org.springframework.web.socket.WebSocketSession;

/**
 * Test {@link WsInboundExecutor} that runs frame work on the calling thread.
 *
 * <p>Handler tests assert on what a frame did, not on where it ran. Keeping
 * them synchronous keeps them deterministic — the alternative would be
 * timeout-based verification in every WebSocket test, which trades a real
 * assertion for a race.
 *
 * <p>The production executor's own behaviour (ordering, bound, discard on
 * close) is covered by {@code OrderedWsInboundExecutorTest}.
 */
final class DirectWsInboundExecutor implements WsInboundExecutor {

    @Override
    public void submit(WebSocketSession wsSession, FrameWork work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void forget(String wsSessionId) {
        // Nothing is queued when everything runs inline.
    }
}
