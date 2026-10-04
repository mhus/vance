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

    /**
     * No metrics, no status asides; plan and the engine turn boundaries
     * ({@code ENGINE_TURN_START}/{@code ENGINE_TURN_END}) are still emitted —
     * structurally important for the clients' busy tracking.
     */
    OFF,

    /**
     * Metrics emitted, status emitted for tool-boundaries and any
     * explicit pings (incl. {@link StatusTag#SCRIPT_PROGRESS} from
     * {@code vance.process.progress(...)}) — only {@link StatusTag#INFO}
     * (catch-all engine asides) is suppressed.
     */
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
            case STATUS ->
                switch (this) {
                    // Turn boundaries are structural, not chatty progress: the
                    // clients' busy spinner, foot's one-shot turn gate and
                    // remote drivers all track turns from them — and since the
                    // persist-bound steer ack (planning/active-message-queue.md
                    // §2) they are the ONLY signal that a turn is in flight. A
                    // verbosity setting may silence asides, never the lifecycle.
                    case OFF -> tag == StatusTag.ENGINE_TURN_START || tag == StatusTag.ENGINE_TURN_END;
                    case NORMAL -> tag != StatusTag.INFO;
                    case VERBOSE -> true;
                };
            // REPLY is semantic engine output, not chatty progress —
            // never silenced by this filter. Parent-inbox routing is
            // load-bearing and must not depend on UI-verbosity settings.
            case REPLY -> true;
        };
    }
}
