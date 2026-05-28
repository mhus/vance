package de.mhus.vance.brain.discovery;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Structured outcome of a {@link DiscoveryService#discover} call.
 * Exactly one of {@link #getLoaded}, {@link #getAlternatives} or
 * {@link #getHint} is meaningfully populated per response — the
 * other fields default to {@code null} / empty.
 *
 * <p>Mirrors the JSON shapes the {@code how-do-i} recipe instructs
 * the LLM to emit. See {@code specification/how-do-i.md} §5.
 */
@Value
@Builder
public class DiscoveryResult {

    /** The intent the caller submitted, echoed for clarity. */
    String intent;

    /** Confident match — caller resolves the body via
     *  {@code manual_read('<name>')}. */
    @Nullable Match loaded;

    /** Ambiguous alternatives — caller picks one and resolves the
     *  body via {@code manual_read('<name>')}. */
    List<Match> alternatives;

    /** No match — short hint string for the caller. */
    @Nullable String hint;

    /**
     * Capability reference returned by the discovery LLM. The same
     * shape is used for {@link DiscoveryResult#getLoaded} (confident
     * single match) and {@link DiscoveryResult#getAlternatives}
     * (ranked candidates). Both flows lead the caller to a
     * {@code manual_read('<name>')} call — the catalog ships summary
     * cards, never full bodies, so the discovery layer never returns
     * raw content inline.
     */
    @Value
    @Builder
    public static class Match {
        /** {@code manual}, {@code skill}, or {@code tool}. */
        String type;
        /** Capability name as it appears in the catalog. */
        String name;
        /** Catalog source attribution, e.g. {@code engine}. */
        @Nullable String source;
        /** One-line summary — usually copied from the catalog card. */
        @Nullable String summary;
        /** Subjective confidence 0–1 (populated for alternatives;
         *  may be present on loaded too). */
        @Nullable Double score;
    }
}
