package de.mhus.vance.shared.document;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Per-mime-type extractor for the parsed front-matter projection that
 * {@link DocumentService} mirrors onto the {@link DocumentDocument}. Each
 * strategy decides whether it handles a given mime-type and, if so, returns
 * the {@link DocumentHeader} (or {@link Optional#empty()} when the body
 * carries no recognisable header).
 *
 * <p>Strategies must be tolerant: malformed input → {@code Optional.empty()}.
 * They never throw.
 */
public interface HeaderStrategy {

    /**
     * {@code true} when this strategy handles the given mime-type. The
     * dispatcher tries strategies in order; the first match wins. Comparison
     * should be case-insensitive and tolerant of {@code ; charset=…} suffixes.
     */
    boolean supports(@Nullable String mimeType);

    /**
     * Extract the front-matter from {@code body}. Empty when no header is
     * present or the format does not parse. Implementations must not throw.
     */
    Optional<DocumentHeader> parse(String body);
}
