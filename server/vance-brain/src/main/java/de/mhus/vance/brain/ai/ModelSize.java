package de.mhus.vance.brain.ai;

/**
 * Coarse capability class of an LLM. Drives prompt-variant selection:
 * a recipe can ship two prompts (default + {@code Small}) and the
 * engine picks the matching variant based on the resolved model's
 * size. Two buckets are intentionally enough — finer gradations are
 * marketing noise that doesn't usefully translate to prompt design.
 *
 * <ul>
 *   <li>{@link #SMALL} — Haiku-class, Flash-class. Needs explicit,
 *       step-by-step instructions; loses thread on long abstract
 *       prompts.</li>
 *   <li>{@link #LARGE} — Sonnet-class, Opus-class, Pro-class. Handles
 *       longer prompts and multi-step planning gracefully.</li>
 * </ul>
 *
 * <p>Default for unknown models is {@link #LARGE}: assuming "more
 * capacity" is the safer guess if the catalog has no entry — a
 * SMALL-tuned prompt may be too sparse for an unknown larger model,
 * but a LARGE-tuned prompt will at least produce a usable answer
 * from a smaller one.
 */
public enum ModelSize {
    SMALL,
    LARGE;

    /**
     * Parses a user-supplied size string for the {@code params.modelSize}
     * recipe / process-spec field. Recognised values (case-insensitive):
     *
     * <ul>
     *   <li>{@code "SMALL"} / {@code "LARGE"} — explicit override.</li>
     *   <li>{@code "AUTO"}, {@code null}, blank, or unknown → returns
     *       {@code fallback}, which is normally the size taken from
     *       {@code ModelCatalog} for the resolved model. {@code AUTO} is
     *       the documented default value; the silent-fallback for
     *       unknown values keeps a typo from breaking a turn.</li>
     * </ul>
     */
    public static ModelSize parseOrAuto(@org.jspecify.annotations.Nullable String input,
                                        ModelSize fallback) {
        if (input == null) return fallback;
        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("AUTO")) {
            return fallback;
        }
        try {
            return valueOf(trimmed.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
