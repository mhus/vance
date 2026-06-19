package de.mhus.vance.api.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Web-UI-facing snapshot of a single shell execution as tracked by the
 * brain's central registry. Mirrors the server-side
 * {@code ExecutionRegistryEntry} but without the on-disk log paths
 * (those are an internal detail; the UI uses the dedicated tail
 * endpoint to look at output).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("execution")
public class ExecutionInsightsDto {

    /** Globally unique id assigned at spawn time. */
    private String id;

    /**
     * Owner-side label. {@code "brain"} for jobs run by the brain pod,
     * {@code "foot:<editorId>"} for jobs run on a foot client.
     */
    private String owner;

    private @Nullable String tenantId;
    private @Nullable String projectId;
    private @Nullable String sessionId;
    private @Nullable String processId;

    /** Verbatim command line as passed to the shell. */
    private String command;

    /** RootDir name the job ran in (brain-side jobs only). */
    private @Nullable String dirName;

    private Instant startedAt;
    private Instant lastOutputAt;
    private @Nullable Instant endedAt;

    /** One of RUNNING / COMPLETED / FAILED / KILLED / ORPHANED. */
    private String status;

    private @Nullable Integer exitCode;
}
