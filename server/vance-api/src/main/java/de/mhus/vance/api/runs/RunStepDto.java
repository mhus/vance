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
}
