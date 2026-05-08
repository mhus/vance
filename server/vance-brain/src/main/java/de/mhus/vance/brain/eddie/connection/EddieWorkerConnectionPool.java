package de.mhus.vance.brain.eddie.connection;

import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory pool of {@link EddieWorkerConnection}s, keyed by
 * {@code (eddieProcessId, workerProcessId)} — one connection per
 * worker {@link WorkerLinkSnapshot} an Eddie process is observing.
 *
 * <p>Pool state is <b>pod-local</b>: User-projects have no home pod
 * (see {@code eddie-engine.md} §2.4), so when an Eddie process resumes
 * on a different pod after a User-WS reconnect, its in-memory pool is
 * empty there. The persistent {@code WorkerLinkSnapshot}-list on
 * Eddie's {@code ThinkProcessDocument} lets the new pod rebuild the
 * connections lazily on first use (see
 * {@link #openOrReuse}).
 *
 * <p>Reconnect-with-backoff is intentionally <em>not</em> in here. The
 * caller (typically Eddie's lane on a turn that wants to send a worker
 * something) catches {@link EddieWorkerConnection.EddieWorkerConnectException}
 * and decides whether to retry now, fall back to inbox-via-Mongo, or
 * tell the user. The pool only opens fresh connections — it doesn't
 * loop on transient errors.
 *
 * <p>Lifecycle: closed Spring bean teardown closes every active
 * connection (best-effort).
 */
@Component
@Slf4j
public class EddieWorkerConnectionPool {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper;

    /** {@code eddieProcessId → workerProcessId → connection}. */
    private final ConcurrentMap<String, ConcurrentMap<String, EddieWorkerConnection>> byEddie =
            new ConcurrentHashMap<>();

    public EddieWorkerConnectionPool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns an existing connection for the given worker, or opens a
     * new one against {@link WorkerLinkSnapshot#getWorkerPodAddress()}
     * with the supplied {@code router} and {@code userJwt}. Throws
     * {@link EddieWorkerConnection.EddieWorkerConnectException} if the
     * connection cannot be opened (network failure, bind rejection,
     * timeout).
     *
     * <p>The {@code router} is captured per worker the first time the
     * connection is opened; later calls reuse it. Pool-level routing
     * therefore stays consistent across the connection's lifetime even
     * if a caller passes a different router instance later.
     */
    public EddieWorkerConnection openOrReuse(
            String eddieProcessId,
            WorkerLinkSnapshot link,
            String userJwt,
            EddieFrameRouter router) {
        ConcurrentMap<String, EddieWorkerConnection> perEddie =
                byEddie.computeIfAbsent(eddieProcessId, k -> new ConcurrentHashMap<>());
        return perEddie.computeIfAbsent(link.getWorkerProcessId(), k -> {
            EddieWorkerConnection conn = createConnection(link, userJwt, router);
            try {
                conn.connect();
            } catch (RuntimeException e) {
                // Don't cache a half-open connection; the next call will retry.
                throw e;
            }
            return conn;
        });
    }

    /**
     * Connection factory hook — overridable in tests so the pool can be
     * exercised without a real WebSocket server. Production builds the
     * real {@link EddieWorkerConnection} against the shared
     * {@link HttpClient}.
     */
    protected EddieWorkerConnection createConnection(
            WorkerLinkSnapshot link, String userJwt, EddieFrameRouter router) {
        return new EddieWorkerConnection(httpClient, objectMapper, router, link, userJwt);
    }

    /** Looks up an existing connection without creating one. */
    public Optional<EddieWorkerConnection> find(String eddieProcessId, String workerProcessId) {
        ConcurrentMap<String, EddieWorkerConnection> perEddie = byEddie.get(eddieProcessId);
        if (perEddie == null) return Optional.empty();
        return Optional.ofNullable(perEddie.get(workerProcessId));
    }

    /**
     * Reverse lookup: given a {@code workerProcessId}, find the Eddie
     * process that opened the observation channel. There is at most
     * one — workers are single-tenant single-user, and the pool keys
     * collisions out via the {@code (eddieProcessId, workerProcessId)}
     * pair. Used by frame handlers that receive a worker frame and
     * need to write back to the owning Eddie's persisted snapshot.
     */
    public Optional<String> findEddieIdForWorker(String workerProcessId) {
        for (Map.Entry<String, ConcurrentMap<String, EddieWorkerConnection>> e : byEddie.entrySet()) {
            if (e.getValue().containsKey(workerProcessId)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Closes one worker connection on the given Eddie process. No-op
     * if the connection isn't open.
     */
    public void close(String eddieProcessId, String workerProcessId) {
        ConcurrentMap<String, EddieWorkerConnection> perEddie = byEddie.get(eddieProcessId);
        if (perEddie == null) return;
        EddieWorkerConnection conn = perEddie.remove(workerProcessId);
        if (conn != null) conn.close();
        if (perEddie.isEmpty()) byEddie.remove(eddieProcessId);
    }

    /**
     * Closes every connection owned by the given Eddie process. Used
     * on Eddie suspend / close, and on pool shutdown.
     */
    public void closeAll(String eddieProcessId) {
        ConcurrentMap<String, EddieWorkerConnection> perEddie = byEddie.remove(eddieProcessId);
        if (perEddie == null) return;
        for (EddieWorkerConnection conn : perEddie.values()) {
            try {
                conn.close();
            } catch (RuntimeException e) {
                log.debug("close failed for worker={}: {}",
                        conn.link().getWorkerProcessId(), e.toString());
            }
        }
    }

    /** Live counter — open connections across all Eddie processes. */
    public int activeConnectionCount() {
        int sum = 0;
        for (Map<String, EddieWorkerConnection> perEddie : byEddie.values()) {
            sum += perEddie.size();
        }
        return sum;
    }

    @PreDestroy
    void shutdown() {
        for (String eddieProcessId : Map.copyOf(byEddie).keySet()) {
            closeAll(eddieProcessId);
        }
    }
}
