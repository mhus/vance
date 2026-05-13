package de.mhus.vance.api.eventlog;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Single event log entry — what the UI run-history shows and what the
 * {@code GET /scheduler/{name}/events} endpoint returns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("eventlog")
public class EventLogEntryDto {

    private String id;
    private String tenantId;
    private @Nullable String projectId;

    /** Convention: {@code "<kind>:<id>"} — e.g. {@code "scheduler:morning-briefing"}. */
    private String source;
    private EventType type;
    private Instant timestamp;

    /** Links the events of one run together ({@code TRIGGERED → STARTED → COMPLETED}). */
    private @Nullable String correlationId;

    private @Nullable String sessionId;
    private @Nullable String processId;
    private @Nullable String runAs;

    /** Type-specific payload. Empty for most events; e.g. carries {@code "error"} on {@code FAILED}. */
    private @Nullable Map<String, Object> payload;
}
