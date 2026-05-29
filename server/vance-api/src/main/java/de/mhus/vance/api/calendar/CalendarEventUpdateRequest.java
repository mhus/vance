package de.mhus.vance.api.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PATCH /calendar/events}. All fields optional —
 * {@code null} means "leave untouched". An empty list / empty string
 * clears the field. {@code targetLane} moves the event across lanes
 * (= writes it into a different source file and removes the old copy).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("calendar")
public class CalendarEventUpdateRequest {

    private @Nullable String title;

    private @Nullable String start;

    private @Nullable String end;

    private @Nullable Boolean allDay;

    private @Nullable String location;

    private @Nullable List<String> attendees;

    private @Nullable String recurrence;

    private @Nullable String color;

    private @Nullable List<String> tags;

    private @Nullable String notes;

    /** Target lane name to move the event into. {@code null} = stay. */
    private @Nullable String targetLane;
}
