package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** A run this run started — sub-workflow, spawned agent, phase worker. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunChildDto {

    /** Composite id, so the view can link straight to it. */
    private String runId;

    private @Nullable String name;

    /** Which step of the parent started it. */
    private @Nullable String fromStep;
}
