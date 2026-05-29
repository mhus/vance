package de.mhus.vance.api.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of {@code POST /calendar/rebuild}. Carries the regenerated
 * artefacts so the planner overview can refresh without a second
 * roundtrip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("calendar")
public class CalendarRebuildResponse {

    private String folder;

    @Builder.Default
    private List<CalendarArtefactSummary> artefacts = new ArrayList<>();
}
