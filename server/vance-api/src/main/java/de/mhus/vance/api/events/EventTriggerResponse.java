package de.mhus.vance.api.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reply body for {@code GET/POST /brain/{tenant}/event/{project}/{event}}
 * — carries the freshly-spawned {@code workflowRunId} so the external
 * caller can correlate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("events")
public class EventTriggerResponse {

    private String event;
    private String workflowName;
    private String workflowRunId;
}
