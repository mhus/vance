package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.toolhealth.HttpStatusError;
import de.mhus.vance.api.toolhealth.RetryAfterError;
import de.mhus.vance.toolpack.core.HttpRetryAfter;
import de.mhus.vance.toolpack.rest.RestHttpInvoker;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Sorts a failed tool call into the {@link MagratheaErrorKind} a workflow can route on.
 *
 * <p>The kinds say what to <em>do</em>: wait and retry ({@code rate_limited},
 * {@code unavailable}), ask a person to sign in again ({@code auth_error}), take the
 * "does not exist" branch ({@code not_found}) — not which status number came back.
 * One place decides, so a REST pack, an MCP server and a transport failure all land
 * in the same kinds.
 *
 * <p>Two things can fail. A call that <em>threw</em> ({@link #classify(Throwable)}),
 * and a REST call that <em>returned</em> {@code {ok: false, status: …}} as an
 * ordinary result ({@link #classifyResult(Map)}): without the second, a 429 would be
 * a {@code success} to the workflow.
 *
 * @param kind       what the workflow routes on
 * @param message    what to tell the reader
 * @param retryAfter when the service asked to be asked again, or {@code null}
 */
public record ToolFailure(
        MagratheaErrorKind kind, String message, @Nullable Instant retryAfter) {

    /** The status → kind table. 2xx and 3xx are no failure and answer {@code null}. */
    public static @Nullable MagratheaErrorKind kindForStatus(int status) {
        if (status < 400) return null;
        return switch (status) {
            case 401 -> MagratheaErrorKind.AUTH_ERROR;
            case 403 -> MagratheaErrorKind.PERMISSION_ERROR;
            case 404 -> MagratheaErrorKind.NOT_FOUND;
            case 429 -> MagratheaErrorKind.RATE_LIMITED;
            case 408, 502, 503, 504 -> MagratheaErrorKind.UNAVAILABLE;
            default -> status >= 500 ? MagratheaErrorKind.TECHNICAL_ERROR : MagratheaErrorKind.BUSINESS_ERROR;
        };
    }

    /**
     * A REST-pack result that says the call failed — {@code ok: false} with a
     * {@code status} — or empty for any other result, which is then a success.
     */
    public static Optional<ToolFailure> classifyResult(@Nullable Map<String, Object> result) {
        if (result == null || !Boolean.FALSE.equals(result.get("ok"))) return Optional.empty();
        if (!(result.get("status") instanceof Number number)) return Optional.empty();
        int status = number.intValue();
        MagratheaErrorKind kind = kindForStatus(status);
        if (kind == null) return Optional.empty();
        Instant retryAfter = null;
        if (result.get("headers") instanceof Map<?, ?> headers) {
            for (Map.Entry<?, ?> header : headers.entrySet()) {
                if ("retry-after".equalsIgnoreCase(String.valueOf(header.getKey()))) {
                    retryAfter = HttpRetryAfter.parse(String.valueOf(header.getValue()));
                }
            }
        }
        return Optional.of(new ToolFailure(kind, "HTTP " + status, retryAfter));
    }

    /**
     * What a thrown tool failure means: its HTTP status when it carries one anywhere in
     * its cause chain, a network failure of the REST pack as {@code unavailable}, and
     * anything else as {@code technical_error}.
     */
    public static ToolFailure classify(Throwable failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        Instant retryAfter = null;
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (retryAfter == null && t instanceof RetryAfterError r) retryAfter = r.retryAfter();
            if (t instanceof HttpStatusError h) {
                MagratheaErrorKind kind = kindForStatus(h.httpStatus());
                if (kind != null) return new ToolFailure(kind, message, retryAfter);
            }
            if (t instanceof RestHttpInvoker.RestInvocationException) {
                return new ToolFailure(MagratheaErrorKind.UNAVAILABLE, message, retryAfter);
            }
        }
        return new ToolFailure(MagratheaErrorKind.TECHNICAL_ERROR, message, retryAfter);
    }
}
