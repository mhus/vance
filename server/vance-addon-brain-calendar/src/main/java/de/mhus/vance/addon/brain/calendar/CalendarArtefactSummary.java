package de.mhus.vance.addon.brain.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Compact reference to a derived artefact ({@code _gantt.md} or
 * {@code _conflicts.yaml}) — path plus a ready-to-paste chat link.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("calendar")
public class CalendarArtefactSummary {

    private String name;

    private String path;

    private @Nullable String markdownLink;

    /** Inline body so the planner overview tab can render the Gantt /
     *  conflict listing without a second roundtrip. */
    private @Nullable String body;

    private @Nullable String mimeType;
}
