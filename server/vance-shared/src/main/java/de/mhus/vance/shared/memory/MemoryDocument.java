package de.mhus.vance.shared.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent engine-side memory entry.
 *
 * <p>Scope is tenant-anchored and may narrow to project / session /
 * process via the corresponding optional fields:
 * <ul>
 *   <li>{@code projectId} only ({@code sessionId} and
 *       {@code thinkProcessId} null): project-wide memory shared by
 *       every session under the project.</li>
 *   <li>+ {@code sessionId}: session-scoped (rare so far).</li>
 *   <li>+ {@code thinkProcessId}: process-private — typical for
 *       compaction summaries and scratchpad.</li>
 * </ul>
 *
 * <p>{@code sourceRefs} ties a memory back to the records it was
 * derived from (e.g. archived chat-message ids). Combined with
 * {@code supersededByMemoryId}, this preserves an audit chain: the
 * old chat messages stay in the database, marked archived; the
 * memory points at them; later compactions point at older memory
 * entries via supersede.
 *
 * <p>{@code metadata} is free-form and kind-specific. Engines add
 * fields on a need-to-know basis instead of widening this entity.
 */
@Document(collection = "memories")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_process_kind_time_idx",
                def = "{ 'tenantId': 1, 'thinkProcessId': 1, 'kind': 1, 'createdAt': 1 }"),
        @CompoundIndex(
                name = "tenant_session_kind_idx",
                def = "{ 'tenantId': 1, 'sessionId': 1, 'kind': 1 }"),
        @CompoundIndex(
                name = "tenant_project_kind_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'kind': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Always set — pod-affinity flows through the project. */
    private String projectId = "";

    /** {@code SessionDocument.sessionId} or null when project-wide. */
    private @Nullable String sessionId;

    /** {@code ThinkProcessDocument.id} or null when broader. */
    private @Nullable String thinkProcessId;

    private MemoryKind kind = MemoryKind.OTHER;

    /** Short human label — "Compaction 2026-04-26", "Plan v3", … */
    private @Nullable String title;

    /** The payload itself: summary text, plan body, scratchpad note. */
    private String content = "";

    /**
     * IDs of records this memory was derived from (e.g. archived
     * {@code ChatMessageDocument} ids for {@link MemoryKind#ARCHIVED_CHAT}).
     */
    @Builder.Default
    private List<String> sourceRefs = new ArrayList<>();

    /** Free-form, kind-specific metadata. */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /** Set when a newer memory replaced this one (compaction chains). */
    private @Nullable String supersededByMemoryId;

    /** When this memory was superseded — paired with {@link #supersededByMemoryId}. */
    private @Nullable Instant supersededAt;

    @CreatedDate
    private @Nullable Instant createdAt;
}
