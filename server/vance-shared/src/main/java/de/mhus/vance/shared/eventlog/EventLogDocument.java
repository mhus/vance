package de.mhus.vance.shared.eventlog;

import de.mhus.vance.api.eventlog.EventType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One entry in the generic event log. Append-only — producers create,
 * UI/queries read, nothing ever updates a row.
 *
 * <p>{@code source} carries the producer kind plus its identifier in
 * {@code "<kind>:<id>"} form, e.g. {@code "scheduler:morning-briefing"}.
 * {@code correlationId} links the events of one logical run together
 * ({@code TRIGGERED → STARTED → COMPLETED}) so the UI can show a run
 * detail without joining on multiple fields.
 *
 * <p>See {@code specification/scheduler.md} §7.
 */
@Document(collection = "event_log")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_source_time_idx",
                def = "{ 'tenantId': 1, 'source': 1, 'timestamp': -1 }"),
        @CompoundIndex(name = "tenant_project_time_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'timestamp': -1 }"),
        @CompoundIndex(name = "correlation_idx", def = "{ 'correlationId': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventLogDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** {@code null} for tenant-wide events. */
    private @Nullable String projectId;

    /** {@code "<kind>:<identifier>"} — e.g. {@code "scheduler:morning-briefing"}. */
    private String source = "";

    private EventType type = EventType.TRIGGERED;

    private Instant timestamp = Instant.EPOCH;

    /** Links events of the same logical run together. */
    private @Nullable String correlationId;

    private @Nullable String sessionId;
    private @Nullable String processId;
    private @Nullable String runAs;

    /** Type-specific extra data — e.g. {@code "error"} for {@link EventType#FAILED}. */
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();
}
