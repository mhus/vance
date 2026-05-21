package de.mhus.vance.api.magrathea;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WS reply to {@link MagratheaWorkflowStartRequest} — carries the
 * freshly-allocated {@code workflowRunId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("magrathea")
public class MagratheaWorkflowStartResponse {

    private String workflowRunId;
    private String workflowName;
}
