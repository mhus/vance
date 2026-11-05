package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/runs/{runId}/simulate}: the result a halted
 * debug run is given instead of executing the state it stands in front of.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunSimulateRequest {

    /**
     * How the state ends. One of the outcomes {@link RunSimulationDto#getOutcomes()}
     * offers — anything else has no way on in the plan and is refused.
     */
    private String outcome;

    /** What the state produced; {@code storeAs:} reads from it. JSON or plain text. Optional. */
    private @Nullable Object output;

    /** For an error outcome: the message the state would have reported. Optional. */
    private @Nullable String errorMessage;
}
