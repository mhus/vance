package de.mhus.vance.api.hactar;

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
 * Snapshot of a workflow run for REST/WS clients. Projected from the
 * journal by {@code HactarStateProjector} — never the source of truth
 * (the journal is). See plan §16 ("Workflow-Status für UI =
 * Journal-Projection + Materialized View").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("hactar")
public class HactarProcessDto {

    /** 8-hex UUIDv4 prefix; stable across pod-restarts. */
    private String workflowRunId;

    /** Workflow definition name as referenced by the cascade. */
    private String workflowName;

    /** Frozen at {@code start()} time, immutable for the rest of the run. */
    private @Nullable String workflowVersion;

    private String tenantId;
    private String projectId;

    private HactarRunStatus status;

    /** Currently-executing state name, or last state on terminal runs. */
    private @Nullable String currentState;

    /** {@code params:} passed at start, after defaulting. */
    private @Nullable Map<String, Object> params;

    /** Materialised workflow variables ({@code storeAs:} + system records). */
    private @Nullable Map<String, Object> vars;

    /** Caller of {@code start} — user id, scheduler key, or hook origin. */
    private @Nullable String startedBy;

    private @Nullable Instant createdAt;
    private @Nullable Instant updatedAt;

    /** Set when status is terminal. */
    private @Nullable Instant terminatedAt;

    /** Set when status is terminal — payload from the {@code terminal} state's {@code result:}. */
    private @Nullable Map<String, Object> result;

    /** Tags inherited from the workflow definition. */
    private @Nullable List<String> tags;
}
