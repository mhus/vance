package de.mhus.vance.addon.brain.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full state of a calendar-app folder — what the
 * {@code GET /calendar/planner} endpoint returns. Lanes are in
 * manifest order, events are flat (per-lane grouping happens
 * client-side via {@code event.lane}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("calendar")
public class CalendarPlannerView {

    private String folder;

    private String manifestPath;

    private @Nullable String title;

    private @Nullable String description;

    private @Nullable String windowFrom;

    private @Nullable String windowUntil;

    @Builder.Default
    private List<CalendarLaneView> lanes = new ArrayList<>();

    @Builder.Default
    private List<CalendarEventView> events = new ArrayList<>();

    @Builder.Default
    private List<CalendarConflictView> conflicts = new ArrayList<>();

    @Builder.Default
    private List<CalendarArtefactSummary> artefacts = new ArrayList<>();
}
