package de.mhus.vance.brain.ai;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Recognises the one provider failure that says "your {@code tools} array
 * is too long", and pulls the real limit out of the message.
 *
 * <p>The rejection is request validation, not inference: no tokens are
 * spent, and every chain entry behind the same endpoint answers
 * identically. Both facts matter for how the resilient layer reacts —
 * advancing the chain is guaranteed waste, and the number in the message
 * is the metadata the catalog was missing.
 *
 * <p>Example (OpenAI wire, observed 2026-08-12):
 * <pre>
 * {"error":{"message":"Invalid 'tools': array too long. Expected an array
 *  with maximum length 128, but got an array with length 163 instead.",
 *  "type":"invalid_request_error","param":"tools",
 *  "code":"array_above_max_length"}}
 * </pre>
 */
public final class ToolLimitError {

    /** Provider-independent fingerprint of "too many tool schemas". */
    private static final Pattern TOO_MANY_TOOLS = Pattern.compile(
            "array_above_max_length"
                    + "|'tools'[^\\n]{0,80}(too long|too many)"
                    + "|too many (?:tools|functions)",
            Pattern.CASE_INSENSITIVE);

    /** The cap itself. Two spellings seen; both carry the number. */
    private static final Pattern LIMIT = Pattern.compile(
            "maximum (?:length|of) (\\d{1,5})", Pattern.CASE_INSENSITIVE);

    private ToolLimitError() {}

    /** Does this error text mean the request carried too many tools? */
    public static boolean isTooManyTools(@Nullable String errorText) {
        return errorText != null && TOO_MANY_TOOLS.matcher(errorText).find();
    }

    /** Walks the cause chain and joins the messages for pattern matching. */
    public static String messageOf(@Nullable Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable t = error;
        int guard = 0;
        while (t != null && guard++ < 10) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append('\n');
            }
            if (t.getCause() == t) break;
            t = t.getCause();
        }
        return sb.toString();
    }

    /** The limit stated in the message, when it states one. */
    public static OptionalInt parseLimit(@Nullable String errorText) {
        if (errorText == null) return OptionalInt.empty();
        Matcher m = LIMIT.matcher(errorText);
        if (!m.find()) return OptionalInt.empty();
        try {
            int value = Integer.parseInt(m.group(1));
            return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
