package de.mhus.vance.brain.progress;

import de.mhus.vance.api.progress.ProgressKind;
import de.mhus.vance.api.progress.StatusTag;
import org.jspecify.annotations.Nullable;

/**
 * Per-process verbosity for the progress side-channel. Read from
 * {@code ThinkProcessDocument.engineParams["progress"]} (string), defaults
 * to {@link #NORMAL} when the param is missing or unparseable.
 *
 * <p>Server-internal — never travels on the wire.
 */
public enum ProgressLevel {

    /** No metrics, no status pings; plan is still emitted (structurally important). */
    OFF,

    /** Metrics emitted, status emitted for tool-boundaries — {@link StatusTag#INFO} suppressed. */
    NORMAL,

    /** All metrics, all status (including {@link StatusTag#INFO} engine asides). */
    VERBOSE;

    /** Recipe / engine-param key. */
    public static final String PARAM_KEY = "progress";

    public static ProgressLevel parse(@Nullable Object raw) {
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return ProgressLevel.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through to default
            }
        }
        return NORMAL;
    }

    /**
     * Whether a payload of {@code kind} (and, for STATUS, the given
     * {@code tag}) should be emitted at this level.
     */
    public boolean allows(ProgressKind kind, @Nullable StatusTag tag) {
        return switch (kind) {
            case PLAN -> true;
            case METRICS -> this != OFF;
            case STATUS -> switch (this) {
                case OFF -> false;
                case NORMAL -> tag != StatusTag.INFO;
                case VERBOSE -> true;
            };
        };
    }
}
