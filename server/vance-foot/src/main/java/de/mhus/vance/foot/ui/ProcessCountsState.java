package de.mhus.vance.foot.ui;

import de.mhus.vance.api.thinkprocess.ProcessCountsNotification;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Holds the most recent {@code process-counts} frame — how many
 * think-processes of the bound session are running / waiting / blocked.
 * Read by {@link StatusBar} for the context segment right of the hints row;
 * the user takes it as the cue to run {@code /process-list}.
 *
 * <p>The frame carries its {@code sessionId}, and {@link #countsFor} only
 * answers for a matching session. That makes a stale count impossible after
 * a session switch without any explicit reset plumbing: the numbers of the
 * previous session simply stop applying, and the server re-pushes on
 * bootstrap / resume.
 *
 * <p>Requirement: planning/process-visibility.md §4.A
 */
@Component
public class ProcessCountsState {

    /** Last frame, or {@code null} until the first push arrives. */
    private volatile @Nullable ProcessCountsNotification last;

    public void apply(ProcessCountsNotification counts) {
        this.last = counts;
    }

    /**
     * The counts for {@code sessionId}, or {@code null} when nothing was
     * pushed yet, the frame belongs to another session, or no process exists
     * (in which case there is nothing to render).
     */
    public @Nullable ProcessCountsNotification countsFor(@Nullable String sessionId) {
        ProcessCountsNotification counts = last;
        if (counts == null || sessionId == null) {
            return null;
        }
        if (!sessionId.equals(counts.getSessionId())) {
            return null;
        }
        return counts.getTotal() <= 0 ? null : counts;
    }
}
