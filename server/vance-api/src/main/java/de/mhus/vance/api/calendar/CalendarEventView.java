package de.mhus.vance.api.calendar;

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
 * One event in a calendar-app folder. {@code id} is the
 * {@code CalendarEvent.id()} — stable across edits unless the event
 * is recreated. {@code sourcePath} carries the path of the calendar
 * file the event lives in (= lane source).
 *
 * <p>{@code addUrls} packs ready-to-paste per-event links for
 * external calendars (Google, Outlook). The planner UI surfaces
 * them as one-click buttons.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("calendar")
public class CalendarEventView {

    private String id;

    private String lane;

    private String sourcePath;

    private String title;

    private String start;

    private @Nullable String end;

    private boolean allDay;

    private @Nullable String location;

    @Builder.Default
    private List<String> attendees = new ArrayList<>();

    private @Nullable String recurrence;

    private @Nullable String color;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private @Nullable String notes;

    /** Google Calendar deep-link for one-click add. */
    private @Nullable String googleUrl;

    /** Outlook Web deep-link for one-click add. */
    private @Nullable String outlookUrl;
}
