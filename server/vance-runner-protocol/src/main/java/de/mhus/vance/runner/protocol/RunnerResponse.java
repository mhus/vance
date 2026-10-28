package de.mhus.vance.runner.protocol;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Header of an answer, inside the age envelope addressed to the request's one-time
 * reply key. Any body (file contents, command output) follows in the same stream.
 *
 * @param v      protocol version
 * @param jti    the {@code jti} of the request this answers — the brain refuses an
 *               answer for any other
 * @param status {@link Status#OK} with a {@code result}, or {@link Status#ERROR} with an
 *               {@code error}
 */
public record RunnerResponse(
        int v,
        String jti,
        Status status,
        @Nullable JsonNode result,
        @Nullable Failure error) {

    public RunnerResponse {
        // Jackson reads a JSON null as a NullNode; "no result" is null here.
        result = result == null || result.isNull() ? null : result;
    }

    public enum Status {
        OK,
        ERROR
    }

    /** A failed operation: a stable {@code code} for programs, a {@code message} for people. */
    public record Failure(String code, String message) {}

    public static RunnerResponse ok(String jti, @Nullable JsonNode result) {
        return new RunnerResponse(RunnerEnvelope.VERSION, jti, Status.OK, result, null);
    }

    public static RunnerResponse error(String jti, String code, String message) {
        return new RunnerResponse(RunnerEnvelope.VERSION, jti, Status.ERROR, null, new Failure(code, message));
    }
}
