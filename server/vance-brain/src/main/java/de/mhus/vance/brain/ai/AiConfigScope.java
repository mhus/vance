package de.mhus.vance.brain.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * Which settings layers a process's AI configuration is resolved from.
 *
 * <p>Every piece of a chat endpoint — model alias, {@code ai.default.*},
 * {@code ai.provider.<instance>.apiKey}, {@code .baseUrl} and the
 * {@link ModelCatalog} view — is read through the project cascade
 * ({@code think-process → project → _tenant}). Because those are
 * <em>independent</em> lookups, an inner layer that redefines only part
 * of the picture silently mixes layers: a project that overrides
 * {@code ai.provider.openai.baseUrl} but inherits its model name from
 * {@code _tenant} sends the tenant's model to the project's endpoint.
 *
 * <p>{@link #TENANT} collapses the cascade to its outermost layer, so
 * model and endpoint provably come from the same place. Selected per
 * recipe via {@code params.aiScope: tenant}.
 *
 * <h2>When to pin</h2>
 * The criterion is <em>not</em> "is this a service engine" but "is this
 * engine's output control-data for others". Agrajag marks tools
 * UNAVAILABLE and sets cooldowns that gate other users' processes in the
 * project — such a decision must not depend on whichever experimental
 * model a project happens to point at. User-facing helpers
 * ({@code how_do_i}, {@code follow-up}) deliberately stay on
 * {@link #CASCADE} and follow the project's model choice.
 *
 * <p>There is intentionally no fallback from {@link #TENANT} back to the
 * project layer: "sometimes tenant, sometimes project, depending on which
 * key happens to be set" is exactly the non-determinism this enum exists
 * to remove. A tenant without AI settings makes the pinned engine fail
 * fast — for a best-effort service that means "no diagnosis", which beats
 * a diagnosis of unknown provenance.
 */
public enum AiConfigScope {

    /**
     * Default — full project cascade
     * ({@code think-process → project → _tenant}). What every engine got
     * before this enum existed.
     */
    CASCADE,

    /**
     * Only the tenant layer ({@code _tenant}). Implemented by passing
     * {@code null} for {@code projectId} and {@code processId} into the
     * settings lookups, which collapses
     * {@link de.mhus.vance.shared.settings.SettingService#getStringValueCascade}
     * to its base layer.
     */
    TENANT;

    /** Recipe / engine-param key carrying the scope name. */
    public static final String PARAM_KEY = "aiScope";

    /**
     * Case-insensitive lookup tolerant of whitespace. {@code null},
     * blank and unknown values yield {@link Optional#empty()} so callers
     * can log once and keep the {@link #CASCADE} default — an unreadable
     * scope must not break the spawn.
     */
    public static Optional<AiConfigScope> fromString(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String normalized = s.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(AiConfigScope.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
