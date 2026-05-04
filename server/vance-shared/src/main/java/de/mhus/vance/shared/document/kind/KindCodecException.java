package de.mhus.vance.shared.document.kind;

import org.jspecify.annotations.Nullable;

/**
 * Raised by the kind-codecs when an on-disk body cannot be parsed
 * into the typed model — invalid JSON/YAML, unsupported mime type,
 * structural violations the resilience rules can't recover from.
 *
 * <p>Most resilience cases (missing optional fields, unknown extra
 * keys, single-doc YAML legacy) do <em>not</em> throw — they're
 * handled silently with passthrough. Throwing is reserved for the
 * cases where there is no valid interpretation: malformed parser
 * input, top-level type mismatch (array where object expected),
 * duplicate ids that would corrupt the model.
 */
public class KindCodecException extends RuntimeException {

    public KindCodecException(String message) {
        super(message);
    }

    public KindCodecException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
