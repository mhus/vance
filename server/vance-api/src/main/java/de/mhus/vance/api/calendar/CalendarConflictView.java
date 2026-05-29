package de.mhus.vance.api.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One detected conflict between two events. Pure read model —
 * regenerated on each planner load from the raw event list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("calendar")
public class CalendarConflictView {

    private String titleA;

    private String laneA;

    private String sourceA;

    private String titleB;

    private String laneB;

    private String sourceB;

    private String overlapStart;

    private String overlapEnd;
}
