package de.mhus.vance.shared.document;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Mime-type-aware front-matter dispatcher. Each {@link HeaderStrategy}
 * registers for a set of mime-types; the first one that {@link
 * HeaderStrategy#supports} the body's mime-type extracts the header.
 *
 * <p>Returns {@link Optional#empty()} when no strategy matches or the
 * matching strategy finds no header. {@link DocumentService} treats both
 * cases identically: clear the {@code kind} / {@code headers} mirror.
 *
 * <p>Key normalisation rules (lower-case, dots→underscores, drop leading
 * {@code $}) are owned here so every strategy mirrors the same on-disk
 * shape — see {@link #normalizeKey}.
 */
@Service
public class DocumentHeaderParser {

    private final List<HeaderStrategy> strategies;

    public DocumentHeaderParser(List<HeaderStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    /**
     * Run the registered strategies in order. First {@link
     * HeaderStrategy#supports} match wins; if that strategy returns empty,
     * we don't fall through to the next strategy — the body's mime-type
     * already declared its format.
     */
    public Optional<DocumentHeader> parse(@Nullable String mimeType, @Nullable String body) {
        if (body == null || body.isEmpty()) return Optional.empty();
        for (HeaderStrategy strategy : strategies) {
            if (strategy.supports(mimeType)) {
                return strategy.parse(body);
            }
        }
        return Optional.empty();
    }

    /**
     * Normalise a header key for MongoDB persistence: dots become underscores
     * (Mongo treats dots as path separators), leading {@code $} is dropped
     * (operator prefix), and the result is lower-cased so lookups stay stable
     * across casing differences in the source. Used by every {@link
     * HeaderStrategy} so the on-disk shape matches across formats.
     */
    public static String normalizeKey(String rawKey) {
        String key = rawKey.trim();
        if (key.startsWith("$")) key = key.substring(1);
        key = key.replace('.', '_');
        return key.toLowerCase();
    }

    /** Strip a trailing {@code "; charset=…"} and lower-case the result. */
    public static String canonicalMime(@Nullable String mimeType) {
        if (mimeType == null) return "";
        String mt = mimeType.toLowerCase().trim();
        int semi = mt.indexOf(';');
        return semi >= 0 ? mt.substring(0, semi).trim() : mt;
    }
}
