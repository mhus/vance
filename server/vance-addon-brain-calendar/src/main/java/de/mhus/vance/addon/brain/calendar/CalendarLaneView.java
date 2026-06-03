package de.mhus.vance.addon.brain.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One lane on a calendar planner. {@code sourcePath} points to the
 * underlying {@code kind: calendar} file ({@code <folder>/<lane>/work.yaml})
 * — the planner UI uses it to scope event mutations to the right file.
 * {@code declared = false} marks lanes that exist on disk but were
 * never listed in {@code _app.yaml}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("calendar")
public class CalendarLaneView {

    private String name;

    private @Nullable String title;

    private @Nullable String color;

    private @Nullable Integer order;

    private int eventCount;

    private boolean declared;

    /** Default source-file path for new events in this lane:
     *  {@code <folder>/<lane>/work.yaml}. */
    private String sourcePath;
}
