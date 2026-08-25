package de.mhus.vance.brain.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * The four properties the inbound executor exists for: it does not run frame
 * work on the caller's thread, it keeps a connection's frames in order, it
 * refuses to grow without bound, and it drops what a closed connection left
 * behind.
 */
class OrderedWsInboundExecutorTest {

    private OrderedWsInboundExecutor executor;
    private VanceBrainProperties properties;

    @BeforeEach
    void setUp() {
        properties = new VanceBrainProperties();
        executor = new OrderedWsInboundExecutor(properties);
    }

    @AfterEach
    void tearDown() {
        // shutdown() is package-private (@PreDestroy) — same reflection hop
        // SessionLifecycleServiceTest uses for LaneScheduler.
        ReflectionTestUtils.invokeMethod(executor, "shutdown");
    }

    @Test
    void submit_returnsBeforeTheWorkFinishes_soASlowHandlerCannotStallTheReadThread()
            throws Exception {
        // The whole point: a handler that waits must not hold the thread the
        // container reads the next frame (and the client's PONG) on.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        executor.submit(session("ws-1"), () -> {
            started.countDown();
            release.await();
        });

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    @Test
    void framesOfOneConnection_runInSubmissionOrder() throws Exception {
        // session-resume must bind before the process-steer behind it is
        // dispatched — a plain thread pool would lose that.
        WebSocketSession ws = session("ws-1");
        List<Integer> seen = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(50);

        for (int i = 0; i < 50; i++) {
            int n = i;
            executor.submit(ws, () -> {
                seen.add(n);
                done.countDown();
            });
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).isSorted();
    }

    @Test
    void differentConnections_areNotSerialisedAgainstEachOther() throws Exception {
        // One wedged connection must not stop every other client on the pod.
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch other = new CountDownLatch(1);

        executor.submit(session("ws-blocked"), blocked::await);
        executor.submit(session("ws-other"), other::countDown);

        assertThat(other.await(2, TimeUnit.SECONDS)).isTrue();
        blocked.countDown();
    }

    @Test
    void queueOverflow_closesTheConnection_ratherThanDroppingFramesSilently()
            throws Exception {
        // A vanished frame is invisible to the client; a closed socket makes
        // it reconnect. Two is enough to prove the bound is enforced.
        properties.setInboundQueueLimit(2);
        WebSocketSession ws = session("ws-flood");
        CountDownLatch release = new CountDownLatch(1);

        executor.submit(ws, release::await);   // occupies the drain loop
        executor.submit(ws, () -> { });        // queued 1
        executor.submit(ws, () -> { });        // queued 2 — at the limit
        executor.submit(ws, () -> { });        // over

        verify(ws, timeout(2000)).close(CloseStatus.SERVICE_OVERLOAD);
        release.countDown();
    }

    @Test
    void forget_dropsQueuedFramesOfAClosedConnection() throws Exception {
        WebSocketSession ws = session("ws-gone");
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);

        executor.submit(ws, () -> {
            inFirst.countDown();
            release.await();
        });
        executor.submit(ws, secondRan::countDown);
        assertThat(inFirst.await(2, TimeUnit.SECONDS)).isTrue();

        executor.forget("ws-gone");
        release.countDown();

        assertThat(secondRan.await(500, TimeUnit.MILLISECONDS))
                .as("frame queued for a closed connection must not run")
                .isFalse();
        assertThat(executor.trackedConnections()).isZero();
    }

    @Test
    void throwingFrame_closesTheConnection_asTheContainerDecoratorWould() throws Exception {
        WebSocketSession ws = session("ws-boom");

        executor.submit(ws, () -> {
            throw new IllegalStateException("boom");
        });

        verify(ws, timeout(2000)).close(CloseStatus.SERVER_ERROR);
    }

    private static WebSocketSession session(String id) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(id);
        return ws;
    }
}
