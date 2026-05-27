package de.mhus.vance.api.scheduler;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /scheduler/{name}} — carries the raw YAML the
 * server stores verbatim and validates server-side. The name comes
 * from the path, not the body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scheduler")
public class SchedulerSaveRequest {

    /** YAML body, must parse to a top-level map with at least {@code cron} and {@code recipe}. */
    private String yaml;
}
