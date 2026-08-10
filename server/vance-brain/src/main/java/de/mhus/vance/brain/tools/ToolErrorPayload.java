package de.mhus.vance.brain.tools;

import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders a failed tool invocation into the JSON the engines hand back
 * to the model as the tool result.
 *
 * <p>Every action engine used to carry its own private
 * {@code errorJson(message)} producing {@code {"error": "<message>"}}.
 * Two things were wrong with that shape, and both were observed in
 * production: the payload is structurally indistinguishable from a
 * successful result (the OpenAI wire format has no error flag on a tool
 * message, unlike Anthropic's {@code is_error}), and with the
 * troubleshooting hint prepended to the message the string opened with
 * advice rather than with the failure. A model skimming
 * {@code {"error":"hint: Match not unique = expand surrounding context …"}}
 * read it as a suggestion, kept going, and told the user the edit was
 * done — while the file had never been touched.
 *
 * <p>So: an explicit {@code ok:false} flag first, then a failure line
 * that opens with {@link #FAILURE_PREFIX}, then the hint in its own
 * field. Key order is deliberate — {@link LinkedHashMap} keeps it in
 * the serialised JSON, and the flag plus the marker are the two things
 * a skimming model sees first.
 */
public final class ToolErrorPayload {

    /**
     * Opens the {@code error} field. Uppercase and unambiguous on
     * purpose: this is the token that has to survive a model skimming
     * the tool result.
     */
    public static final String FAILURE_PREFIX = "TOOL CALL FAILED: ";

    private ToolErrorPayload() {}

    /** Error payload without recovery advice. */
    public static Map<String, Object> map(String message) {
        return map(message, null);
    }

    /** Error payload carrying the failing tool's troubleshooting hint. */
    public static Map<String, Object> map(String message, @Nullable String hint) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", FAILURE_PREFIX + (message == null || message.isBlank()
                ? "tool reported no reason" : message));
        if (hint != null && !hint.isBlank()) {
            out.put("hint", hint);
        }
        return out;
    }

    /** Serialised {@link #map(String)}. */
    public static String json(ObjectMapper mapper, String message) {
        return json(mapper, message, null);
    }

    /**
     * Serialised error payload for a {@link ToolException}, picking up
     * its {@link ToolException#getHint() hint} automatically.
     */
    public static String json(ObjectMapper mapper, ToolException e) {
        return json(mapper, e.getMessage(), e.getHint());
    }

    /** Serialised {@link #map(String, String)}. */
    public static String json(ObjectMapper mapper, String message, @Nullable String hint) {
        Map<String, Object> payload = map(message, hint);
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException fail) {
            // Serialising a two-string map cannot realistically fail, but a
            // tool result the model never sees would be the worst possible
            // outcome here — hand-roll rather than propagate.
            return "{\"ok\":false,\"error\":\"" + escape(String.valueOf(payload.get("error")))
                    + "\"}";
        }
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
