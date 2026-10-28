package de.mhus.vance.shared.runner;

import de.mhus.vance.runner.protocol.RunnerEnvelope;
import de.mhus.vance.runner.protocol.RunnerEnvelope.OpenedBytes;
import de.mhus.vance.runner.protocol.RunnerEnvelope.SealedRequest;
import de.mhus.vance.runner.protocol.RunnerProtocolException;
import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import de.mhus.vance.runner.protocol.RunnerResponse;
import de.mhus.vance.shared.jwt.JwtService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The brain's side of a call to a runner ({@code planning/runner.md} §4): finds the
 * project's runner, mints a one-minute token bound to it, seals the request to its
 * registered key, POSTs it, and reads the answer with the one-time key.
 *
 * <p>The runner URL is <b>not</b> run through the SSRF guard on purpose: it is set
 * by the operator through the admin shell (never by a tenant, an agent or a kit) and
 * is typically an address inside the cluster, which the guard would refuse. What
 * protects the call instead is the envelope — a wrong host cannot read the request
 * and cannot answer it.
 */
@Service
@Slf4j
public class RunnerClient {

    /** A runner token is good for about this long; the runner accepts at most five minutes. */
    static final Duration TOKEN_LIFETIME = Duration.ofSeconds(60);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    /** Pseudo project for tenant-level calls (the tenant's own {@code _tenant} project). */
    public static final String TENANT_PROJECT = "_tenant";

    private final RunnerBindingService bindings;
    private final JwtService jwt;
    private final HttpClient http;

    public RunnerClient(RunnerBindingService bindings, JwtService jwt) {
        this.bindings = bindings;
        this.jwt = jwt;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // the runner speaks plain HTTP/1.1
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** What the runner said, and how long the call took. */
    public record Reply(RunnerResponse header, byte[] body, RunnerBinding runner, Duration elapsed) {

        public boolean ok() {
            return header.status() == RunnerResponse.Status.OK;
        }
    }

    /** Why a call did not produce an answer from the registered runner. */
    public static class RunnerCallException extends RuntimeException {

        /** Coarse reason, for callers that react differently. */
        public enum Reason {
            /** No runner is registered for the project or tenant. */
            NO_RUNNER,
            /** The runner could not be reached or timed out. */
            UNREACHABLE,
            /** The runner answered, but not with an HTTP success. */
            REJECTED,
            /** What came back is not an answer from the registered runner. */
            UNTRUSTED
        }

        private final Reason reason;

        public RunnerCallException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public RunnerCallException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /**
     * Calls {@code op} on the runner serving {@code projectId} (the tenant's, with
     * {@code null}).
     *
     * @param actingUser for the token's audit subject
     * @param rootDir    RootDir the call is confined to, or {@code null}
     * @throws RunnerCallException when there is no runner, it cannot be reached, or the
     *     answer cannot be trusted; a runner-side failure is a {@link Reply} with
     *     {@link Reply#ok()} false instead
     */
    public Reply call(
            String tenantId,
            @Nullable String projectId,
            String actingUser,
            @Nullable String rootDir,
            String op,
            @Nullable Object args,
            byte[] body) {
        return call(tenantId, projectId, actingUser, rootDir, op, args, body, DEFAULT_TIMEOUT);
    }

    public Reply call(
            String tenantId,
            @Nullable String projectId,
            String actingUser,
            @Nullable String rootDir,
            String op,
            @Nullable Object args,
            byte[] body,
            Duration timeout) {
        RunnerBindingService.Effective effective = bindings.resolve(tenantId, projectId)
                .orElseThrow(() -> new RunnerCallException(
                        RunnerCallException.Reason.NO_RUNNER,
                        "No runner is registered for " + tenantId + (projectId == null ? "" : "/" + projectId)));
        RunnerBinding runner = effective.binding();
        String jti = UUID.randomUUID().toString();
        String token = jwt.createRunnerToken(
                tenantId,
                actingUser,
                runner.getRunnerId(),
                projectId == null ? TENANT_PROJECT : projectId,
                rootDir,
                jti,
                Instant.now().plus(TOKEN_LIFETIME));
        SealedRequest sealed = RunnerEnvelope.sealRequest(runner.getRecipient(), token, jti, op, args, body);

        Instant started = Instant.now();
        HttpResponse<byte[]> response = send(runner, sealed.bytes(), timeout);
        int status = response.statusCode();
        if (status != 200) {
            throw new RunnerCallException(
                    RunnerCallException.Reason.REJECTED,
                    "Runner '" + runner.getRunnerId() + "' answered HTTP " + status + explain(status));
        }
        OpenedBytes<RunnerResponse> opened;
        try {
            opened = sealed.reply().openResponse(response.body());
        } catch (RunnerProtocolException e) {
            String why = e.kind() == Kind.WRONG_REPLY
                    ? "the answer is not from the registered runner (wrong key registered, or the address leads elsewhere)"
                    : e.getMessage();
            throw new RunnerCallException(
                    RunnerCallException.Reason.UNTRUSTED, "Runner '" + runner.getRunnerId() + "': " + why, e);
        }
        return new Reply(opened.header(), opened.body(), runner, Duration.between(started, Instant.now()));
    }

    private HttpResponse<byte[]> send(RunnerBinding runner, byte[] sealedRequest, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(runner.getUrl() + "/v1/call"))
                .timeout(timeout)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(sealedRequest))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new RunnerCallException(
                    RunnerCallException.Reason.UNREACHABLE,
                    "Runner '" + runner.getRunnerId() + "' at " + runner.getUrl() + " is not reachable: "
                            + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunnerCallException(
                    RunnerCallException.Reason.UNREACHABLE,
                    "Interrupted while calling runner '" + runner.getRunnerId() + "'",
                    e);
        }
    }

    private static String explain(int status) {
        return switch (status) {
            case 400 -> " — it could not read the request (is the registered key the runner's?)";
            case 413 -> " — the request is larger than the runner accepts";
            default -> "";
        };
    }
}
