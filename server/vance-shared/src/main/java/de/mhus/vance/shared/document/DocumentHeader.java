package de.mhus.vance.shared.document;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Parsed front-matter of a markdown document — the small typed projection
 * that {@link DocumentService} mirrors to the {@link DocumentDocument} on
 * save so that queries can find documents by {@code kind} without scanning
 * the body.
 *
 * <p>The truth always stays in {@code inlineText}: this header is reparsed
 * on every save, never written back from the entity. See {@code CLAUDE.md}
 * §"Frontmatter / typed documents".
 *
 * <p>{@link #values} carries every {@code key: value} line from the front
 * matter, in the order they appeared. Keys are normalised so they survive
 * MongoDB persistence — see {@link DocumentHeaderParser} for the rules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentHeader {

    /** {@code kind:} value, the privileged first-class header. */
    private @Nullable String kind;

    /** All header key/value pairs (including {@code kind} when present). */
    @Builder.Default
    private Map<String, String> values = new LinkedHashMap<>();
}
