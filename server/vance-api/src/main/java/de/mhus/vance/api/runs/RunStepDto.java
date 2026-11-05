package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One step a run went through — a workflow state, a strategy phase, a
 * compose task. Ordered oldest first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunStepDto {

    private String name;

    /** {@code null} while the step is still the current one. */
    private @Nullable String outcome;

    private @Nullable Instant at;

    /** Type or role of the step, for the icon — {@code agent_task}, {@code phase}, … */
    private @Nullable String kind;

    /** Free-text detail: error message, recipe, command. */
    private @Nullable String detail;

    /**
     * How long the step's work took, in milliseconds. {@code null} while the
     * step is still running, and for sources that do not measure it.
     */
    private @Nullable Long durationMs;

    /**
     * What the step produced, as readable text (pretty-printed JSON for a
     * structured result). {@code null} when the step has produced nothing yet
     * or nothing worth showing. Cut at a fixed length — see
     * {@link #outputTruncated}.
     */
    private @Nullable String output;

    /** {@code true} when {@link #output} is only the beginning of what the step produced. */
    private boolean outputTruncated;

    /** The step did not run: its result was given to the run by a debug reader. */
    private boolean simulated;
}
