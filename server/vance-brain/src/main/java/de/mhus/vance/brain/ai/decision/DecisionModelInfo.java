package de.mhus.vance.brain.ai.decision;

import org.jspecify.annotations.Nullable;

/**
 * Static facts about a decision model — the {@code kind: decision}
 * catalog entries.
 *
 * <p>The limits differ a lot between models (measured 2026-10-12: Jev
 * takes 255 choice options and structured criteria, Ollama's
 * {@code nimble} 26 options and string criteria only), so they are data
 * here and checked before the call, never left to a provider 400.
 * <b>Defaults are the minimal profile</b> — what every measured model
 * accepts — so an entry that omits a field errs on the safe side.
 *
 * @param provider            instance name the entry lives under
 * @param modelName           provider-specific model id
 * @param timeoutSeconds      per-call HTTP timeout
 * @param maxQuestions        questions per call; more are split over
 *                            several calls by the service
 * @param maxChoiceOptions    options per CHOICE question (min is 2)
 * @param maxScoreLevels      levels per SCORE question (min is 2)
 * @param structuredCriteria  whether descriptions inside {@code criteria}
 *                            may be maps/lists instead of strings
 * @param maxStateTokens      advertised state budget, {@code null} = unknown
 * @param costPerMillionInput USD per million input tokens, {@code null} =
 *                            unpriced (the vendor-reported cost wins when
 *                            the provider sends one)
 * @param rateLimitRps        default request rate per instance and pod,
 *                            {@code null} = unthrottled
 * @param maxConcurrentCalls  default parallel calls per instance and pod,
 *                            {@code null} = unbounded
 */
public record DecisionModelInfo(
        String provider,
        String modelName,
        int timeoutSeconds,
        int maxQuestions,
        int maxChoiceOptions,
        int maxScoreLevels,
        boolean structuredCriteria,
        @Nullable Integer maxStateTokens,
        @Nullable Double costPerMillionInput,
        @Nullable Double rateLimitRps,
        @Nullable Integer maxConcurrentCalls) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_QUESTIONS = 64;
    public static final int DEFAULT_MAX_CHOICE_OPTIONS = 26;
    /** System-One upper bound; no entry may raise it. */
    public static final int MAX_SCORE_LEVELS = 10;
    /** Lower bound for choice options and score levels on every provider. */
    public static final int MIN_OPTIONS = 2;

    public DecisionModelInfo {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
        if (maxQuestions <= 0) {
            maxQuestions = DEFAULT_MAX_QUESTIONS;
        }
        if (maxChoiceOptions < MIN_OPTIONS) {
            maxChoiceOptions = DEFAULT_MAX_CHOICE_OPTIONS;
        }
        if (maxScoreLevels < MIN_OPTIONS || maxScoreLevels > MAX_SCORE_LEVELS) {
            maxScoreLevels = MAX_SCORE_LEVELS;
        }
        if (rateLimitRps != null && rateLimitRps <= 0) {
            rateLimitRps = null;
        }
        if (maxConcurrentCalls != null && maxConcurrentCalls <= 0) {
            maxConcurrentCalls = null;
        }
    }

    /** Cost in USD for {@code inputTokens}, or {@code null} when unpriced. */
    public @Nullable Double costForInputTokens(long inputTokens) {
        return costPerMillionInput == null ? null : costPerMillionInput * inputTokens / 1_000_000d;
    }
}
