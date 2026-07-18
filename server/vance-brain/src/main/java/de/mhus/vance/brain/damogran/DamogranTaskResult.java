package de.mhus.vance.brain.damogran;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Result of a single {@link DamogranTask} execution.
 *
 * <p>Errors are transported in this envelope ({@code status=FAILURE} +
 * {@code error}), not via a separate mechanism — a failed task surfaces its
 * error in the notebook output region like a cell traceback. {@code log} holds
 * optional captured stdout/diagnostics for display.
 *
 * @param status  SUCCESS or FAILURE
 * @param outputs artifacts the task produced (workspace-relative)
 * @param error   failure message, {@code null} on success
 * @param log     optional captured output / diagnostics
 */
public record DamogranTaskResult(
        DamogranStatus status,
        List<OutputArtifact> outputs,
        @Nullable String error,
        @Nullable String log) {

    public boolean isSuccess() {
        return status == DamogranStatus.SUCCESS;
    }

    public static DamogranTaskResult success(List<OutputArtifact> outputs) {
        return new DamogranTaskResult(DamogranStatus.SUCCESS, List.copyOf(outputs), null, null);
    }

    public static DamogranTaskResult success(List<OutputArtifact> outputs, @Nullable String log) {
        return new DamogranTaskResult(DamogranStatus.SUCCESS, List.copyOf(outputs), null, log);
    }

    public static DamogranTaskResult failure(String error) {
        return new DamogranTaskResult(DamogranStatus.FAILURE, List.of(), error, null);
    }

    public static DamogranTaskResult failure(String error, @Nullable String log) {
        return new DamogranTaskResult(DamogranStatus.FAILURE, List.of(), error, log);
    }
}
