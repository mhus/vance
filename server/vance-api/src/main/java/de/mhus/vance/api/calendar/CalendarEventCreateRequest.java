package de.mhus.vance.api.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /calendar/events}. The server appends the new
 * event to {@code <folder>/<lane>/work.yaml}, creating the file if
 * missing. {@code lane} defaults server-side to {@code "common"} when
 * null/empty.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("calendar")
public class CalendarEventCreateRequest {

    private @Nullable String lane;

    @NotBlank
    private String title;

    @NotBlank
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
}
