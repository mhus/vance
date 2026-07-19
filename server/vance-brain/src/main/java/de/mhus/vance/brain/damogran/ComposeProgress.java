package de.mhus.vance.brain.damogran;

import org.jspecify.annotations.Nullable;

/**
 * Live-progress sink a task reports into during an async compose run. Kept
 * minimal: the only thing a task surfaces is the {@link de.mhus.vance.brain.tools.exec.ExecManager}
 * job backing it, so the poll endpoint can show that job's tail as progress.
 * {@code null} for synchronous runs (no tracking).
 */
public interface ComposeProgress {

    /** Report (or clear with {@code null}) the exec job id backing the current task. */
    void execJob(@Nullable String jobId);
}
