package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One row in the run list, whatever runtime produced it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunSummaryDto {

    /**
     * Composite id: {@code <source>:<native id>}, e.g.
     * {@code workflow:2f1c…} or {@code process:6a7d…}. The prefix is not
     * decoration — a 32-hex run id and a Mongo object id are otherwise
     * indistinguishable, and the view would have to guess which source to
     * ask.
     */
    private String runId;

    /** Which {@code RunSource} owns it — {@code workflow}, {@code process}, {@code compose}. */
    private String source;

    /** Human label: workflow name, process name, compose title. */
    private String name;

    private RunStatus status;

    /** Where it currently is — a workflow state, a strategy phase, a task. */
    private @Nullable String step;

    private String projectId;

    private @Nullable String startedBy;
    private @Nullable Instant startedAt;
    private @Nullable Instant updatedAt;

    /** Composite id of the run that spawned this one, when there is one. */
    private @Nullable String parentRunId;
}
