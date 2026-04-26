package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent think-process record.
 *
 * <p>References follow CLAUDE.md: {@code sessionId} is
 * {@link de.mhus.vance.shared.session.SessionDocument#getSessionId()}
 * (business id, not Mongo id); {@code name} is the process's own unique
 * identifier within its session; {@code thinkEngine} is the
 * {@code ThinkEngine.name()} from the registry.
 *
 * <p>{@link #version} enables optimistic locking for concurrent lane
 * state transitions.
 */
@Document(collection = "think_processes")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_session_name_idx",
                def = "{ 'tenantId': 1, 'sessionId': 1, 'name': 1 }",
                unique = true),
        @CompoundIndex(
                name = "tenant_session_status_idx",
                def = "{ 'tenantId': 1, 'sessionId': 1, 'status': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThinkProcessDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Owning session — {@code SessionDocument.sessionId}. */
    private String sessionId = "";

    /** Unique within the session, e.g. {@code "chat"} or a generated worker name. */
    private String name = "";

    /** Optional display name for UI. */
    private @Nullable String title;

    /** Engine name from the registry, e.g. {@code "zaphod"}, {@code "arthur"}, {@code "deep-think"}. */
    private String thinkEngine = "";

    /** Engine version at creation time — for resume compatibility checks. */
    private @Nullable String thinkEngineVersion;

    /** Optional goal for batch-style engines; reactive engines leave this null. */
    private @Nullable String goal;

    /**
     * Mongo id of the orchestrator process that spawned this one.
     * {@code null} for top-level processes (e.g. the session's chat).
     * Used to route life-cycle {@code ProcessEvent}s back to the parent
     * via the pending queue + Auto-Wakeup.
     */
    private @Nullable String parentProcessId;

    /**
     * Persistent inbox: messages that arrived while the process was
     * not in a lane-turn (or that arrived while it was running and
     * must wait for the next one). Drained atomically by
     * {@code ThinkProcessService.drainPending(...)} at the start of
     * each turn.
     *
     * <p>Default to a mutable list so {@code $push} doesn't have to
     * upsert on first use — a freshly-created process simply has an
     * empty queue.
     */
    @Builder.Default
    private List<PendingMessageDocument> pendingMessages = new ArrayList<>();

    private ThinkProcessStatus status = ThinkProcessStatus.READY;

    @Version
    private @Nullable Long version;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;
}
