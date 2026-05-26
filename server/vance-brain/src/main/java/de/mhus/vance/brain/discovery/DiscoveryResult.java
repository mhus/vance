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

    /** Confident match — capability content already loaded. */
    @Nullable Match loaded;

    /** Ambiguous alternatives — caller picks and calls manual_read. */
    List<Match> alternatives;

    /** No match — short hint string for the caller. */
    @Nullable String hint;

    /**
     * Capability reference returned by the discovery LLM. Same shape
     * for both {@link DiscoveryResult#getLoaded} (with {@code content}
     * populated) and entries in
     * {@link DiscoveryResult#getAlternatives} (with {@code summary}
     * + {@code score} instead).
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
        /** Full content (only populated for {@link DiscoveryResult#loaded}). */
        @Nullable String content;
        /** One-line summary (only populated for alternatives). */
        @Nullable String summary;
        /** Subjective confidence 0–1 (only populated for alternatives). */
        @Nullable Double score;
    }
}
