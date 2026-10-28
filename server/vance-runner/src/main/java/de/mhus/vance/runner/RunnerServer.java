package de.mhus.vance.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.RunnerOperation.OperationResult;
import de.mhus.vance.runner.protocol.JtiCache;
import de.mhus.vance.runner.protocol.RunnerEnvelope;
import de.mhus.vance.runner.protocol.RunnerEnvelope.OpenedRequest;
import de.mhus.vance.runner.protocol.RunnerProtocolException;
import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import de.mhus.vance.runner.protocol.RunnerRequest;
import de.mhus.vance.runner.protocol.RunnerResponse;
import de.mhus.vance.runner.protocol.RunnerTokens;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runner's HTTP surface — two routes on the JDK server, no TLS of its own
 * ({@code planning/runner.md} R6):
 *
 * <ul>
 *   <li>{@code GET /v1/health} — {@code 200} with a bare {@code ok}; nothing about the
 *       runner leaves unencrypted.</li>
 *   <li>{@code POST /v1/call} — the body is an age envelope. What the runner says back
 *       depends on how far it got: an envelope it cannot open or a malformed header is
 *       a bare {@code 400} (there is no key to answer to), a body over the limit
 *       {@code 413}; once the envelope is open it always answers <em>inside</em> an
 *       envelope — including a refused token, a wrong tenant or an unknown operation.</li>
 * </ul>
 */
public final class RunnerServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunnerServer.class);

    private static final int REPLAY_CACHE_SIZE = 100_000;

    private final RunnerConfig config;
    private final RunnerIdentity identity;
    private final List<PublicKey> tenantKeys;
    private final Map<String, RunnerOperation> operations;
    private final Clock clock;
    private final JtiCache replay = new JtiCache(REPLAY_CACHE_SIZE);
    private final HttpServer http;

    public RunnerServer(
            RunnerConfig config,
            RunnerIdentity identity,
            List<PublicKey> tenantKeys,
            List<RunnerOperation> operations,
            Clock clock) {
        this.config = config;
        this.identity = identity;
        this.tenantKeys = List.copyOf(tenantKeys);
        this.operations =
                operations.stream().collect(Collectors.toUnmodifiableMap(RunnerOperation::name, Function.identity()));
        this.clock = clock;
        try {
            this.http = HttpServer.create(new InetSocketAddress(config.port()), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot listen on port " + config.port() + ": " + e.getMessage(), e);
        }
        http.createContext("/v1/health", this::health);
        http.createContext("/v1/call", this::call);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        http.start();
    }

    /** The port actually bound — differs from the configured one when that was 0. */
    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(1);
    }

    // ───────────────────── routes ─────────────────────

    private void health(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                empty(exchange, 405);
                return;
            }
            byte[] ok = "ok".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
        }
    }

    private void call(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                empty(exchange, 405);
                return;
            }
            if (declaredLength(exchange) > config.maxRequestBytes()) {
                empty(exchange, 413);
                return;
            }
            OpenedRequest opened;
            try {
                opened = RunnerEnvelope.openRequest(
                        identity.identity(),
                        new LimitedInputStream(exchange.getRequestBody(), config.maxRequestBytes()));
            } catch (RunnerProtocolException e) {
                log.debug("Request refused before it could be answered: {}", e.getMessage());
                empty(exchange, 400);
                return;
            } catch (TooLarge e) {
                empty(exchange, 413);
                return;
            }
            RunnerRequest request = opened.header();
            Optional<String> jti = RunnerTokens.unverifiedJti(request.token());
            if (jti.isEmpty()) {
                empty(exchange, 400); // no call id: an answer could not be bound to anything
                return;
            }
            answer(exchange, request, jti.get(), opened.body());
        }
    }

    /** From here on every outcome is an encrypted answer. */
    private void answer(HttpExchange exchange, RunnerRequest request, String jti, InputStream body) throws IOException {
        RunnerResponse header;
        OperationResult result = null;
        try {
            RunnerTokens.Claims claims =
                    RunnerTokens.verify(request.token(), tenantKeys, config.runnerId(), clock.instant(), replay);
            if (!claims.tenantId().equals(config.tenantId())) {
                throw new OperationException(
                        "wrong-tenant", "This runner does not serve tenant '" + claims.tenantId() + "'");
            }
            RunnerOperation operation = operations.get(request.op());
            if (operation == null) {
                throw new OperationException("unknown-op", "Unknown operation '" + request.op() + "'");
            }
            result = operation.execute(new RunnerOperation.Call(claims, request.args(), body));
            drain(body);
            header = RunnerResponse.ok(jti, result.result());
        } catch (RunnerProtocolException e) {
            header =
                    RunnerResponse.error(jti, e.kind() == Kind.TOKEN ? "token-refused" : "bad-request", e.getMessage());
        } catch (OperationException e) {
            header = RunnerResponse.error(jti, e.code(), e.getMessage());
        } catch (TooLarge e) {
            // A chunked body whose size only showed while reading it.
            header = RunnerResponse.error(jti, "request-too-large", "The request is larger than this runner accepts");
        } catch (RuntimeException e) {
            log.warn("Operation '{}' failed unexpectedly", request.op(), e);
            header = RunnerResponse.error(jti, "internal", "The runner failed on this call");
        }
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, 0); // chunked: bodies stream
        try (OutputStream out = exchange.getResponseBody()) {
            RunnerEnvelope.sealResponse(request.replyKey(), header, result == null ? null : result.body(), out);
        }
    }

    /** Reads what an operation left unread — age only vouches for a stream at its end. */
    private static void drain(InputStream body) throws OperationException {
        try {
            body.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new OperationException("corrupt-body", "The request body is corrupt: " + e.getMessage(), e);
        }
    }

    /** The {@code Content-Length} the client announced, or {@code -1} (chunked / absent). */
    private static long declaredLength(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    /** The request body ran past {@link RunnerConfig#maxRequestBytes()}. */
    private static final class TooLarge extends RuntimeException {
        TooLarge() {
            super("request too large");
        }
    }

    /** Counts bytes and refuses to deliver more than {@code limit}. */
    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                consume(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0) {
                consume(n);
            }
            return n;
        }

        private void consume(long n) {
            remaining -= n;
            if (remaining < 0) {
                throw new TooLarge();
            }
        }
    }
}
