package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One queued message in a Trillian user-loop's inbox. The inbox mixes
 * unstarted task requests with results the loop still has to report —
 * which of the two is waiting changes what an operator should do about
 * it, so the kind is part of the wire contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class TrillianPendingEntryInsightsDto {

    /**
     * Task event kind ({@code task_request}, {@code task_done},
     * {@code task_failed}, {@code task_needs_input}) or {@code message}
     * for anything that is not a task event.
     */
    private String kind;

    private @Nullable String taskId;

    private @Nullable String description;

    private @Nullable Instant queuedAt;
}
