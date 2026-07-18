package de.mhus.vance.brain.damogran;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Result of a whole compose run.
 *
 * <p>The run is linear and halts at the first failing task (see
 * {@code planning/damogran-system.md}); {@code taskResults} therefore covers
 * the tasks that actually ran, in order. {@code workspaceName} identifies the
 * (session-scoped) workspace the run operated on, so a caller can re-run
 * individual tasks against it.
 *
 * @param status        overall SUCCESS (all tasks ran) or FAILURE
 * @param workspaceName the workspace the run used
 * @param taskResults   per-task results, in execution order
 * @param error         first failure message, {@code null} on success
 */
public record DamogranComposeResult(
        DamogranStatus status,
        String workspaceName,
        List<DamogranTaskResult> taskResults,
        @Nullable String error) {

    public boolean isSuccess() {
        return status == DamogranStatus.SUCCESS;
    }
}
