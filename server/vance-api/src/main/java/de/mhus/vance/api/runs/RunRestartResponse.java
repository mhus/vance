package de.mhus.vance.api.runs;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Answer of {@code POST /brain/{tenant}/runs/{runId}/restart}: the run that was started. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("runs")
public class RunRestartResponse {

    /** Composite id of the new run, so the view can go straight to it. */
    private String runId;
}
