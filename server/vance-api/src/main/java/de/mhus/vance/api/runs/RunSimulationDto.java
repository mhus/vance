package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a halted debug run offers to simulate: the state it stands in front of,
 * what kind of task that is, and the outcomes the plan knows a way on for.
 * Present on {@link RunDetailDto} only while the run can be given a result
 * instead of executing — the view builds its form from it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunSimulationDto {

    /** The state the run stands in front of. */
    private String state;

    /** Task type of that state, lower case ({@code tool_task}, {@code gate_task}, …). */
    private String kind;

    /** Outcomes that lead somewhere: the {@code on:} keys, then the error kinds of {@code catch:}. */
    @Builder.Default
    private List<String> outcomes = List.of();
}
