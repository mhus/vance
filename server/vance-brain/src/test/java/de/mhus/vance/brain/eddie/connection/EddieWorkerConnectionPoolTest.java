package de.mhus.vance.brain.eddie.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pool-lifecycle tests. Connection creation is mocked through the
 * {@code createConnection} hook so we exercise the pool without a real
 * WebSocket server (that's QA-territory).
 */
class EddieWorkerConnectionPoolTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void openOrReuse_opensOnce_thenReusesForSameWorker() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger connects = new AtomicInteger();
        TestPool pool = new TestPool(httpClient, objectMapper, creates, connects, false);

        WorkerLinkSnapshot link = link("w-1");
        EddieWorkerConnection a = pool.openOrReuse("eddie-1", link, "jwt", EddieFrameRouter.noOp());
        EddieWorkerConnection b = pool.openOrReuse("eddie-1", link, "jwt", EddieFrameRouter.noOp());

        assertThat(a).isSameAs(b);
        assertThat(creates.get()).isEqualTo(1);
        assertThat(connects.get()).isEqualTo(1);
        assertThat(pool.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void openOrReuse_distinctEntriesPerWorker_evenSameEddie() {
        TestPool pool = new TestPool(httpClient, objectMapper,
                new AtomicInteger(), new AtomicInteger(), false);

        pool.openOrReuse("eddie-1", link("w-1"), "jwt", EddieFrameRouter.noOp());
        pool.openOrReuse("eddie-1", link("w-2"), "jwt", EddieFrameRouter.noOp());

        assertThat(pool.activeConnectionCount()).isEqualTo(2);
        assertThat(pool.find("eddie-1", "w-1")).isPresent();
        assertThat(pool.find("eddie-1", "w-2")).isPresent();
    }

    @Test
    void connectFailure_doesNotCacheHalfOpen() {
        TestPool pool = new TestPool(httpClient, objectMapper,
                new AtomicInteger(), new AtomicInteger(), /*throwOnConnect=*/ true);

        assertThatThrownBy(() -> pool.openOrReuse(
                "eddie-1", link("w-broken"), "jwt", EddieFrameRouter.noOp()))
                .isInstanceOf(RuntimeException.class);

        assertThat(pool.find("eddie-1", "w-broken")).isEmpty();
        assertThat(pool.activeConnectionCount()).isZero();
    }

    @Test
    void close_oneWorker_keepsTheOthers() {
        TestPool pool = new TestPool(httpClient, objectMapper,
                new AtomicInteger(), new AtomicInteger(), false);

        pool.openOrReuse("eddie-1", link("w-1"), "jwt", EddieFrameRouter.noOp());
        pool.openOrReuse("eddie-1", link("w-2"), "jwt", EddieFrameRouter.noOp());

        pool.close("eddie-1", "w-1");

        assertThat(pool.find("eddie-1", "w-1")).isEmpty();
        assertThat(pool.find("eddie-1", "w-2")).isPresent();
    }

    @Test
    void closeAll_dropsEveryConnectionFor_thatEddie() {
        TestPool pool = new TestPool(httpClient, objectMapper,
                new AtomicInteger(), new AtomicInteger(), false);

        pool.openOrReuse("eddie-1", link("w-1"), "jwt", EddieFrameRouter.noOp());
        pool.openOrReuse("eddie-1", link("w-2"), "jwt", EddieFrameRouter.noOp());
        pool.openOrReuse("eddie-2", link("w-3"), "jwt", EddieFrameRouter.noOp());

        pool.closeAll("eddie-1");

        assertThat(pool.find("eddie-1", "w-1")).isEmpty();
        assertThat(pool.find("eddie-1", "w-2")).isEmpty();
        assertThat(pool.find("eddie-2", "w-3")).isPresent();
    }

    @Test
    void findEddieIdForWorker_reverseLookup() {
        TestPool pool = new TestPool(httpClient, objectMapper,
                new AtomicInteger(), new AtomicInteger(), false);

        pool.openOrReuse("eddie-1", link("w-1"), "jwt", EddieFrameRouter.noOp());
        pool.openOrReuse("eddie-2", link("w-2"), "jwt", EddieFrameRouter.noOp());

        assertThat(pool.findEddieIdForWorker("w-1")).contains("eddie-1");
        assertThat(pool.findEddieIdForWorker("w-2")).contains("eddie-2");
        assertThat(pool.findEddieIdForWorker("w-missing")).isEmpty();
    }

    private static WorkerLinkSnapshot link(String workerProcessId) {
        return WorkerLinkSnapshot.builder()
                .workerProcessId(workerProcessId)
                .workerProcessName("arthur")
                .workerProjectName("p")
                .workerSessionId("s")
                .workerPodAddress("127.0.0.1:8080")
                .build();
    }

    /**
     * Pool with an injectable connection-factory that records lifecycle
     * counters and never opens a real WebSocket.
     */
    private static final class TestPool extends EddieWorkerConnectionPool {

        private final AtomicInteger creates;
        private final AtomicInteger connects;
        private final boolean throwOnConnect;

        TestPool(HttpClient httpClient, ObjectMapper objectMapper,
                 AtomicInteger creates, AtomicInteger connects, boolean throwOnConnect) {
            super(objectMapper);
            this.creates = creates;
            this.connects = connects;
            this.throwOnConnect = throwOnConnect;
        }

        @Override
        protected EddieWorkerConnection createConnection(
                WorkerLinkSnapshot link, String userJwt, EddieFrameRouter router) {
            creates.incrementAndGet();
            return new MockConnection(link, userJwt, router, connects, throwOnConnect);
        }
    }

    /**
     * No-op subclass of {@link EddieWorkerConnection} — overrides
     * connect/close so we never touch the network.
     */
    private static final class MockConnection extends EddieWorkerConnection {
        private final AtomicInteger connects;
        private final boolean throwOnConnect;

        MockConnection(WorkerLinkSnapshot link, String userJwt, EddieFrameRouter router,
                       AtomicInteger connects, boolean throwOnConnect) {
            super(HttpClient.newHttpClient(), JsonMapper.builder().build(), router, link, userJwt);
            this.connects = connects;
            this.throwOnConnect = throwOnConnect;
        }

        @Override
        public void connect() {
            if (throwOnConnect) {
                throw new EddieWorkerConnectException("test failure");
            }
            connects.incrementAndGet();
        }

        @Override
        public void close() {
            // no-op — no real socket to close
        }
    }
}
