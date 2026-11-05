package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/runs/{runId}/restart}: start a new run
 * from an ended one. Without {@code fromState} it is a rerun — the same
 * parameters, from the start state. With it, the new run begins in that state
 * with the variables the old run had when it last entered it.
 *
 * <p>Always against the definition the old run froze; a changed definition
 * would not fit the old variables.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunRestartRequest {

    /** The state to begin in; absent for a rerun from the start. */
    private @Nullable String fromState;

    /** Start the new run in step mode, like the same flag of a start. */
    private @Nullable Boolean debug;

    /** States the new run halts before, like the same list of a start. */
    private @Nullable List<String> breakpoints;
}
