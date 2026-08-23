package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.UsageKind;
import de.mhus.vance.shared.llmusage.UsageOutcome;
import org.jspecify.annotations.Nullable;

/**
 * What one model-call attempt consumed, and which endpoint served it. Pure
 * measurement — carries no identity of the <i>caller</i>, because that is
 * the caller's answer ({@link de.mhus.vance.shared.llmusage.CallAttribution})
 * and this is the wire's.
 *
 * <p>Deliberately flat rather than holding a {@link ModelInfo}: an image
 * call is described by {@link de.mhus.vance.brain.ai.image.ImageModelInfo}
 * and would otherwise need a synthetic chat entry invented for it. What the
 * ledger needs from either is the same four facts — who served it, what it
 * was called, what it cost per unit, and how big its context is.
 *
 * <p>{@code providerInstance} matters separately from {@code providerType}:
 * a fallback chain can answer from a different instance than the one that
 * was asked for, and the row has to name the one that actually billed.
 */
public record UsageMeasurement(
        /** Instance label — {@code openai}, {@code cortecs}. */
        String providerInstance,
        /** Wire protocol — what SDK spoke to it. */
        String providerType,
        /** Concrete model as the provider names it. */
        String providerModel,
        /** Rate snapshot; {@code null} means the catalog has no price for it. */
        ModelInfo.@Nullable Pricing pricing,
        @Nullable Integer contextWindowTokens,
        UsageKind kind,
        UsageOutcome outcome,
        /** 1-based; values above 1 are retries or fallback-chain advances. */
        int attempt,
        int tokensIn,
        int tokensOut,
        int cacheReadTokens,
        int cacheWriteTokens,
        /** Generated images, {@link UsageKind#IMAGE} only. */
        int images,
        /** Flat amount for an image call, {@link UsageKind#IMAGE} only. */
        @Nullable Double imageCost,
        /** Currency of {@link #imageCost}; token pricing carries its own. */
        @Nullable String imageCurrency,
        long durationMs) {

    /** Chat round-trip that produced an answer. */
    public static UsageMeasurement chat(
            ModelInfo model,
            String providerInstance,
            int attempt,
            int tokensIn,
            int tokensOut,
            int cacheReadTokens,
            int cacheWriteTokens,
            long durationMs) {
        return of(model, providerInstance, UsageOutcome.SUCCESS, attempt,
                tokensIn, tokensOut, cacheReadTokens, cacheWriteTokens, durationMs);
    }

    /** Chat attempt that raised. Token counts are whatever the provider reported. */
    public static UsageMeasurement chatFailed(
            ModelInfo model,
            String providerInstance,
            int attempt,
            int tokensIn,
            int tokensOut,
            long durationMs) {
        return of(model, providerInstance, UsageOutcome.FAILED, attempt,
                tokensIn, tokensOut, 0, 0, durationMs);
    }

    /** Embedding batch — one row per batch, not per chunk. */
    public static UsageMeasurement embedding(
            ModelInfo model, String providerInstance, int tokensIn, long durationMs) {
        return new UsageMeasurement(
                providerInstance, model.provider(), model.modelName(), model.pricing(),
                model.contextWindowTokens() > 0 ? model.contextWindowTokens() : null,
                UsageKind.EMBEDDING, UsageOutcome.SUCCESS, 1,
                tokensIn, 0, 0, 0, 0, null, null, durationMs);
    }

    /** One generated image, priced per image rather than per token. */
    public static UsageMeasurement image(
            String providerInstance,
            String providerType,
            String providerModel,
            @Nullable Double cost,
            String currency,
            UsageOutcome outcome,
            long durationMs) {
        return new UsageMeasurement(
                providerInstance, providerType, providerModel, null, null,
                UsageKind.IMAGE, outcome, 1,
                0, 0, 0, 0, 1, cost, currency, durationMs);
    }

    private static UsageMeasurement of(
            ModelInfo model,
            String providerInstance,
            UsageOutcome outcome,
            int attempt,
            int tokensIn,
            int tokensOut,
            int cacheReadTokens,
            int cacheWriteTokens,
            long durationMs) {
        return new UsageMeasurement(
                providerInstance, model.provider(), model.modelName(), model.pricing(),
                model.contextWindowTokens() > 0 ? model.contextWindowTokens() : null,
                UsageKind.CHAT, outcome, attempt,
                tokensIn, tokensOut, cacheReadTokens, cacheWriteTokens,
                0, null, null, durationMs);
    }

    /**
     * Alias written to the row: {@code instance:model}. Built here so every
     * writer produces the same shape — the two former writers disagreed on
     * this field, which is how per-model reports grew duplicate entries.
     */
    public String modelAlias() {
        return providerInstance + ":" + providerModel;
    }

    /** Currency for this row: the rate snapshot's, or the image currency. */
    public @Nullable String resolvedCurrency() {
        if (kind == UsageKind.IMAGE) return imageCurrency;
        return pricing == null ? null : pricing.currency();
    }

    /**
     * Nothing worth writing. A successful attempt for which the provider
     * reported no usage gives us no information — but a <b>failed</b>
     * attempt is itself the information, even with no counts, because the
     * prompt went out and the vendor may well have charged for it.
     */
    public boolean isEmpty() {
        if (outcome == UsageOutcome.FAILED) return false;
        return tokensIn <= 0 && tokensOut <= 0
                && cacheReadTokens <= 0 && cacheWriteTokens <= 0
                && images <= 0;
    }
}
