package de.mhus.vance.api.scheduler;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full scheduler document for the editor — both the parsed fields and
 * the raw YAML body. The YAML is the source of truth (round-trips
 * verbatim, including comments); parsed fields are convenience for the
 * UI to render the form without re-parsing.
 *
 * <p>See {@code specification/scheduler.md} §2 for the YAML schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scheduler")
public class SchedulerDto {

    /** Scheduler name — derived from the document path, not stored in the YAML body. */
    private String name;

    /** Verbatim YAML body as stored in the document layer. */
    private String yaml;

    /** Which cascade tier currently provides this scheduler. */
    private SchedulerSource source;

    // ─── Parsed convenience fields (mirror of YAML for the UI) ───

    private @Nullable String description;

    /** Recurring trigger — mutually exclusive with {@link #at}. */
    private @Nullable String cron;

    /** One-shot fire instant — mutually exclusive with {@link #cron}. */
    private @Nullable Instant at;

    private @Nullable String timezone;

    /** {@code true} unless the YAML explicitly sets {@code enabled: false}. */
    private boolean enabled;

    private @Nullable String recipe;
    private @Nullable Map<String, Object> params;
    private @Nullable String initialMessage;
    private @Nullable String runAs;
    private @Nullable OverlapPolicy overlap;

    /** Default {@link LockMode#FULL} — see {@code specification/scheduler.md} §10b. */
    private @Nullable LockMode lockMode;

    private @Nullable List<String> tags;
}
