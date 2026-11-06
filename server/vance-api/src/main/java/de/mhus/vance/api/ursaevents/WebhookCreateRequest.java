package de.mhus.vance.api.ursaevents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Make an event that starts a workflow when something calls its URL with the event's bearer token. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("events")
public class WebhookCreateRequest {

    /** Name of the workflow to start. */
    private String workflow;

    /** Name of the event; becomes its document name, its URL segment and part of its token's setting key. */
    private @Nullable String name;

    /** What the event is for; a line is made up from the workflow when absent. */
    private @Nullable String description;
}
