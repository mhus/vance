package de.mhus.vance.api.ursaevents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reply body for {@code GET/POST /brain/{tenant}/event/{project}/{event}}.
 *
 * <p>Carries either the freshly-spawned {@code workflowRunId} — so the
 * external caller can correlate and poll — or the {@code output} of an
 * action that ran to completion. Which one appears follows the action:
 * a script answers synchronously unless the event declares
 * {@code async: true}, a recipe or workflow spawn always reports only
 * that it started.
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

    /**
     * What the action produced, when it produced anything. A script's
     * return value is mapped by the same convention the workflow layer
     * uses: a scalar or array arrives under {@code value}, an object
     * arrives as itself.
     */
    private Map<String, Object> output;
}
