package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.toolpack.research.SearchProviderHttpException;
import de.mhus.vance.toolpack.research.SearchQuotaExceededException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * How a non-2xx answer becomes the typed errors the dispatcher understands.
 * One place, because three protocols (SearXNG, Exa, Firecrawl) would
 * otherwise each write the same classification — and getting it wrong is not
 * cosmetic: 402 must become a quota <em>state</em> (the instance steps aside,
 * the cascade continues) while 429 must become a hard failure whose
 * {@code Retry-After} drives the cooldown.
 *
 * <p>Message shape is {@code "<prefix> returned HTTP <status>: <error text>"}
 * so Agrajag's status extraction and {@code bodyContains} patterns match on
 * the text as well.
 */
final class ProtocolErrors {

    private ProtocolErrors() {
        /* static only */
    }

    /**
     * @param prefix     who and what, e.g. {@code "Exa 'exa-main' /search"}
     * @param response   the non-2xx response
     * @param resetsAt   when the provider said quota comes back — only read
     *                   for 402, {@code null} when it did not say
     */
    static RuntimeException httpFailure(
            ObjectMapper mapper, String prefix, JsonHttpClient.JsonResponse response, @Nullable Instant resetsAt) {
        String errorText = errorText(mapper, response.body());
        String message =
                prefix + " returned HTTP " + response.statusCode() + (errorText.isEmpty() ? "" : ": " + errorText);
        if (response.statusCode() == 402) {
            // "Payment Required: Insufficient credits" — a known, terminating
            // state. Not analysed by Agrajag; the dispatcher cools the
            // instance down until its quota returns and cascades on.
            return new SearchQuotaExceededException(message, resetsAt);
        }
        return new SearchProviderHttpException(
                message, response.statusCode(), JsonHttpClient.retryAfter(response.headers()));
    }

    /**
     * The provider's own error sentence from the body. Firecrawl and Exa both
     * answer errors as JSON ({@code {"error": "…"}} resp.
     * {@code {"success": false, "error": "…"}}); anything else falls back to a
     * collapsed excerpt so the message still says what happened.
     */
    private static String errorText(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (root != null && root.isObject()) {
                for (String key : new String[] {"error", "message"}) {
                    JsonNode value = root.path(key);
                    if (value.isTextual() && !value.asText().isBlank()) {
                        return JsonHttpClient.bodyExcerpt(value.asText(), 200);
                    }
                }
            }
        } catch (RuntimeException notJson) {
            /* fall through to the raw excerpt */
        }
        return JsonHttpClient.bodyExcerpt(body, 200);
    }
}
