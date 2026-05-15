package de.mhus.vance.api.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Compact list-view entry for the events list in the Web-UI insights
 * editor. Mirror of {@link EventDto} without the raw YAML body —
 * the listing renders identity / target / auth-status only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("events")
public class EventSummary {

    private String name;
    private @Nullable String description;

    /** Workflow name this event spawns when triggered. */
    private @Nullable String workflow;

    private boolean enabled;

    /** Allowed HTTP methods. Empty list = both {@code GET} and {@code POST}. */
    private @Nullable List<String> methods;

    /** {@code true} when an inline token or {@code tokenSetting:} is set. */
    private boolean authConfigured;

    /** {@code "bearer"} or {@code "none"} — convenience for the UI. */
    private @Nullable String authType;

    private EventSource source;

    private @Nullable List<String> tags;
}
