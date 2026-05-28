package de.mhus.vance.api.ursascheduler;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Compact list-view entry for the scheduler list in the Web-UI and
 * the {@code scheduler_list} agent tool. Doesn't carry the YAML body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scheduler")
public class SchedulerSummary {

    private String name;
    private @Nullable String description;
    private @Nullable String cron;
    private @Nullable String recipe;
    private @Nullable String runAs;
    private boolean enabled;
    private SchedulerSource source;

    /** Default {@link LockMode#FULL} — see {@code specification/scheduler.md} §10b. */
    private @Nullable LockMode lockMode;

    /** Timestamp of the most recent {@code STARTED}/{@code COMPLETED}/{@code FAILED}/{@code SKIPPED} event. */
    private @Nullable Instant lastRunAt;

    /** Computed locally from the cron expression — not persisted. */
    private @Nullable Instant nextRunAt;
}
