package de.mhus.vance.foot.ui;

import de.mhus.vance.api.progress.MetricsPayload;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Last-seen cumulative LLM usage for the currently active chat round-
 * trip, read by {@link StatusBar} so the spinner row can show running
 * token and character counts next to the elapsed time.
 *
 * <p>Updated by
 * {@link de.mhus.vance.foot.connection.handlers.ProcessProgressHandler}
 * on every {@code MetricsPayload} push, independent of the user's
 * verbosity filter — the side-channel HUD line may be suppressed but
 * the spinner-bar number should always reflect the latest tick.
 *
 * <p>Cleared when the {@link BusyIndicator} transitions back to idle
 * (managed by {@link StatusBar} since it already tracks the
 * busy → idle edge for its phrase/elapsed reset).
 */
@Component
public class LiveUsageState {

    private final AtomicReference<@Nullable Snapshot> latest = new AtomicReference<>();

    /** Replace the snapshot with the latest cumulative numbers. */
    public void update(MetricsPayload m) {
        if (m == null) return;
        latest.set(new Snapshot(
                m.getTokensInTotal(),
                m.getTokensOutTotal(),
                m.getCharsInTotal(),
                m.getCharsOutTotal(),
                m.getLlmCallCount()));
    }

    /** Drop the snapshot — called when the brain finishes a turn. */
    public void clear() {
        latest.set(null);
    }

    public @Nullable Snapshot snapshot() {
        return latest.get();
    }

    /** Immutable view of the last cumulative metrics push. */
    public record Snapshot(
            long tokensIn,
            long tokensOut,
            long charsIn,
            long charsOut,
            int calls) {}
}
