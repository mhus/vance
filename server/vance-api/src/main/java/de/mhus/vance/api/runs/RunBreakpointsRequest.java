package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /brain/{tenant}/runs/{runId}/breakpoints}: the complete set
 * of states a debuggable run is to halt before from now on. An empty list
 * clears them; the run stays debuggable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunBreakpointsRequest {

    /** The whole set after the change, not a delta. */
    @Builder.Default
    private List<String> breakpoints = List.of();
}
