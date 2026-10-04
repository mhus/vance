package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One live per-task worker spawned by a Trillian user-loop — a child
 * process in the worker session. The loop itself is reported as
 * {@link TrillianWorkerInsightsDto} and never appears here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class TrillianTaskWorkerInsightsDto {

    private String name;

    private String processId;

    /**
     * Target project of the worker — for a cross-project spawn this
     * differs from the worker session's own project, which is exactly
     * what makes the spawn visible here.
     */
    private String projectId;

    private ThinkProcessStatus status;

    private String engine;

    private @Nullable Instant createdAt;
}
