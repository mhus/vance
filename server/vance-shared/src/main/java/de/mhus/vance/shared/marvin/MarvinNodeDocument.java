package de.mhus.vance.shared.marvin;

import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent task-tree node owned by a Marvin think-process.
 * See {@code specification/marvin-engine.md} §2 for the full
 * data-model and {@code §3} for the status transitions.
 *
 * <p>Per Marvin process there are potentially hundreds of nodes —
 * embedding them in {@code ThinkProcessDocument} would risk the
 * 16-MB Mongo limit on bigger trees, so they live in their own
 * collection.
 *
 * <p>Indexes:
 * <ul>
 *   <li>{@code (processId, status, position)} — hot-path
 *       "next actionable node" query.</li>
 *   <li>{@code (processId, parentId, position)} — children-in-order.</li>
 *   <li>{@code spawnedProcessId} — reverse lookup when a worker's
 *       ProcessEvent arrives.</li>
 *   <li>{@code inboxItemId} — reverse lookup when an InboxAnswer
 *       arrives.</li>
 * </ul>
 */
@Document(collection = "marvin_nodes")
@CompoundIndexes({
        @CompoundIndex(
                name = "process_status_position_idx",
                def = "{ 'processId': 1, 'status': 1, 'position': 1 }"),
        @CompoundIndex(
                name = "process_parent_position_idx",
                def = "{ 'processId': 1, 'parentId': 1, 'position': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarvinNodeDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Owning Marvin think-process Mongo-id. */
    private String processId = "";

    /** Parent node Mongo-id; {@code null} for the tree's root. */
    private @Nullable String parentId;

    /** Sortable index among siblings with the same {@code parentId}.
     *  Smaller comes first. Re-numbering is allowed (e.g. when a
     *  worker inserts a new sibling via {@code marvin_add_subtask}). */
    private int position;

    /** Human-readable goal — what this node should achieve.
     *  LLM-readable: this is what the planner sees / what the
     *  worker gets as its initial steer. */
    private String goal = "";

    private TaskKind taskKind = TaskKind.PLAN;

    /** Type-specific spec — recipe + steer-content for WORKER,
     *  inbox-item shape for USER_INPUT, prompt-override for PLAN
     *  / AGGREGATE, etc. */
    @Builder.Default
    private Map<String, Object> taskSpec = new LinkedHashMap<>();

    @Builder.Default
    private NodeStatus status = NodeStatus.PENDING;

    /** Output of this node — worker reply for WORKER, user answer
     *  for USER_INPUT, synthesized summary for AGGREGATE/PLAN. */
    @Builder.Default
    private Map<String, Object> artifacts = new LinkedHashMap<>();

    /** Set when {@link #status} is {@link NodeStatus#FAILED}. */
    private @Nullable String failureReason;

    /** Set when {@code taskKind == WORKER} and the worker has been
     *  spawned. Indexed for the ProcessEvent → node reverse lookup. */
    @Indexed(name = "spawned_process_idx", sparse = true)
    private @Nullable String spawnedProcessId;

    /** Set when {@code taskKind == USER_INPUT} and the inbox item
     *  has been created. Indexed for the InboxAnswer → node
     *  reverse lookup. */
    @Indexed(name = "inbox_item_idx", sparse = true)
    private @Nullable String inboxItemId;

    @CreatedDate
    private @Nullable Instant createdAt;

    private @Nullable Instant startedAt;

    private @Nullable Instant completedAt;

    @Version
    private @Nullable Long version;
}
