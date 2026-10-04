package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only state of one Trillian pair for the insights inspector: the
 * control chat a human opens, plus the unattended user-loop it is paired
 * with.
 *
 * <p>{@link #worker} is {@code null} while no loop is built — the
 * activation gate is closed ({@code trillian.enabled}), the model gate
 * refused the loop's model, or the bootstrap has not run yet. The control
 * conversation stays usable either way.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class TrillianInsightsDto {

    private TrillianControlInsightsDto control;

    private @Nullable TrillianWorkerInsightsDto worker;

    /**
     * Whether {@code trillian.enabled} would let a new user-loop start
     * right now — resolved over the hub → project → tenant cascade with
     * the worker account as the hub key. Running loops are not touched by
     * this flag; it gates starts only.
     */
    private boolean loopsEnabled;

    /** Live per-task workers the loop spawned (worker-session children). */
    private List<TrillianTaskWorkerInsightsDto> taskWorkers;

    /** The worker's pending inbox as it stands, newest state first come. */
    private List<TrillianPendingEntryInsightsDto> pending;
}
