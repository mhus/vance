package de.mhus.vance.brain.ai.image;

import java.util.Map;
import java.util.Set;

/**
 * Static facts about an image-generation provider/model pair —
 * supported aspect ratios, prompt-length limit, per-image cost
 * (keyed by quality tier), and a per-call HTTP timeout.
 *
 * <p>Sourced from {@code ai-models.yaml} entries with
 * {@code kind: image} and looked up by
 * {@link de.mhus.vance.brain.ai.ModelCatalog#lookupImage}.
 *
 * <p>Costs are stored per quality tier ({@code "standard"} /
 * {@code "hd"} for OpenAI, single-entry {@code "standard"} for
 * vendors with one tier) so the per-call billing path can pick the
 * right rate from the request's quality dimension. An empty map
 * means "cost unknown" — the call still runs, but the
 * {@code ImageCallTracker} can only count it, not bill it.
 */
public record ImageModelInfo(
        String provider,
        String modelName,
        Set<String> supportedAspectRatios,
        int maxPromptChars,
        Map<String, Double> costPerImage,
        int timeoutSeconds) {

    /** Per-call HTTP timeout used when the catalog entry doesn't carry one. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 360;

    /** Default per-call prompt cap when the catalog entry doesn't set one. */
    public static final int DEFAULT_MAX_PROMPT_CHARS = 1000;

    /** Fallback aspect-ratio set when the catalog entry omits the list. */
    public static final Set<String> DEFAULT_ASPECT_RATIOS = Set.of("1:1");

    public ImageModelInfo {
        supportedAspectRatios = supportedAspectRatios == null || supportedAspectRatios.isEmpty()
                ? DEFAULT_ASPECT_RATIOS
                : Set.copyOf(supportedAspectRatios);
        costPerImage = costPerImage == null ? Map.of() : Map.copyOf(costPerImage);
        if (maxPromptChars <= 0) {
            maxPromptChars = DEFAULT_MAX_PROMPT_CHARS;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /** Whether {@code aspectRatio} is in the supported set. */
    public boolean supportsAspectRatio(String aspectRatio) {
        return supportedAspectRatios.contains(aspectRatio);
    }

    /**
     * Cost in USD for one image at the given quality tier, or
     * {@code null} if no rate is configured for that tier (the
     * caller is expected to skip cost-tracking in that case).
     */
    public Double costFor(String qualityTier) {
        return costPerImage.get(qualityTier);
    }
}
