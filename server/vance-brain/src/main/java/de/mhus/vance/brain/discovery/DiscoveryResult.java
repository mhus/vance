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
     * Capability reference returned by the discovery LLM. Same shape
     * for {@link DiscoveryResult#getLoaded} (confident single match)
     * and entries in {@link DiscoveryResult#getAlternatives} (ranked
     * candidates).
     *
     * <p>{@code content} is server-side-loaded: when the LLM picks a
     * {@code type: manual} entry for {@code loaded}, the
     * {@link DiscoveryService} resolves the name against the document
     * cascade and inlines the manual body here so the caller gets
     * everything in one hop. Hallucinated names trigger a retry loop
     * (max 3) before the result downgrades to a hint. The LLM never
     * supplies {@code content} itself — it's never given the bodies.
     * Alternatives stay name + summary only; the caller picks one and
     * does its own {@code manual_read}.
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
        /** Full body, server-loaded for {@code loaded} + {@code type:manual}.
         *  Always {@code null} for alternatives. */
        @Nullable String content;
        /** One-line summary — usually copied from the catalog card. */
        @Nullable String summary;
        /** Subjective confidence 0–1 (populated for alternatives;
         *  may be present on loaded too). */
        @Nullable Double score;
    }
}
