package de.mhus.vance.brain.ai.iris;

import dev.langchain4j.exception.HttpException;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the error body Iris answers with
 * ({@code {"error":{"code":…,"message":…,"retry_after_seconds":…}}}) and
 * decides what the caller should see.
 *
 * <p>Only refusals that waiting does not fix become an
 * {@link IrisRefusedException}. Everything else — {@code too_many_concurrent},
 * {@code rate_limited}, {@code no_healthy_target}, {@code accounting_unavailable},
 * a provider failure — stays an {@link HttpException}, which langchain4j's own
 * mapping and {@link de.mhus.vance.brain.ai.RetryPolicy} already retry by
 * status.
 */
final class IrisErrors {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Codes after which a retry cannot help: quota, plan, request shape, credentials. */
    private static final Set<String> FINAL_CODES = Set.of(
            "quota_exhausted",
            "window_exhausted",
            "model_not_allowed",
            "model_not_found",
            "wrong_kind",
            "wrong_wire",
            "tenant_disabled",
            "invalid_key",
            "invalid_request");

    private IrisErrors() {}

    /**
     * The exception to throw instead of {@code error}: an
     * {@link IrisRefusedException} for a final refusal, otherwise the original.
     */
    static RuntimeException map(HttpException error) {
        JsonNode body = parse(error.getMessage());
        if (body == null) {
            return error;
        }
        String code = body.path("error").path("code").asString("");
        if (!FINAL_CODES.contains(code)) {
            return error;
        }
        String reason = body.path("error").path("message").asString(code);
        long seconds = body.path("error").path("retry_after_seconds").asLong(0);
        Duration retryAfter = seconds > 0 ? Duration.ofSeconds(seconds) : null;
        return new IrisRefusedException(code, error.statusCode(), reason, retryAfter);
    }

    /** As {@link #map(HttpException)} for a throwable of unknown kind. */
    static Throwable map(Throwable error) {
        return error instanceof HttpException http ? map(http) : error;
    }

    private static @Nullable JsonNode parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        int open = text.indexOf('{');
        if (open < 0) {
            return null;
        }
        try {
            return MAPPER.readTree(text.substring(open));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
