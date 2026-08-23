package de.mhus.vance.shared.inbox;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.inbox.ResolvedBy;
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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent inbox item — answers AND outputs ({@link MaximegalonType}).
 * See {@code specification/user-interaction.md} §3 for the full shape.
 */
@Document(collection = "maximegalon_threads")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_assigned_status_crit_idx",
                def = "{ 'tenantId': 1, 'assignedToUserId': 1, 'status': 1, 'criticality': 1 }"),
        @CompoundIndex(
                name = "tenant_session_status_idx",
                def = "{ 'tenantId': 1, 'originSessionId': 1, 'status': 1 }"),
        @CompoundIndex(
                name = "process_status_idx",
                def = "{ 'originProcessId': 1, 'status': 1 }"),
        // The badge query: "threads with something unread for me, not archived".
        // It fires on every page mount and every tab focus in every MPA entry,
        // so it must be one index on one collection. unreadFor is the multikey
        // equality term, status the range/inequality term — hence this order.
        @CompoundIndex(
                name = "tenant_unread_status_idx",
                def = "{ 'tenantId': 1, 'unreadFor': 1, 'status': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaximegalonDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Who created it (immutable, audit). */
    private String originatorUserId = "";

    /** Who's currently assigned (mutable via delegation). */
    private String assignedToUserId = "";

    /** Originating process Mongo-id; {@code null} when no process is
     *  blocked on this item (pure tool-driven posting). */
    private @Nullable String originProcessId;

    /** Originating session-id (business id), for session-level filtering. */
    private @Nullable String originSessionId;

    private MaximegalonType type = MaximegalonType.OUTPUT_TEXT;

    @Builder.Default
    private Criticality criticality = Criticality.NORMAL;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private String title = "";

    /** Markdown body — long-form description / prompt text. */
    private @Nullable String body;

    /** Type-specific structured data (options for DECISION, schema
     *  for STRUCTURE_EDIT, url for OUTPUT_IMAGE, etc.). */
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();

    /**
     * Server-side effect to run when this item is answered — the
     * {@code effectType()} of an {@link InboxEffect} bean, or {@code null}
     * for the ordinary case where answering only routes back to the
     * asking process.
     *
     * <p>Deliberately a typed field rather than an entry in
     * {@link #payload}: what an answer executes is a security-relevant
     * property of the item, not type-specific render data.
     */
    private @Nullable String effectType;

    /**
     * Identifies what the effect should act on — typically the id of a
     * document holding the pending mutation (e.g. a permission request).
     * Opaque to the inbox; only the effect implementation interprets it.
     */
    private @Nullable String effectRef;

    @Builder.Default
    private MaximegalonStatus status = MaximegalonStatus.PENDING;

    /** {@code true} when the originating process expects an answer
     *  (asks). {@code false} for pure outputs. */
    private boolean requiresAction;

    /** Set when {@link #status} is {@code ANSWERED} or {@code DISMISSED}. */
    private @Nullable AnswerPayload answer;

    private @Nullable ResolvedBy resolvedBy;
    private @Nullable Instant resolvedAt;
    private @Nullable String resolverReason;

    // ── Thread: access, participation, read state, discussion ──
    //
    // Three access sources, all explicit and all additive. assignedToUserId
    // above says who is *up*, and it alone decides who may answer; the two
    // below only widen who may *see* and contribute.

    /**
     * The team that may look on without being a participant. {@code null}
     * keeps the historical behaviour, where visibility is derived from the
     * teams of the current assignee.
     *
     * <p>Declaring it fixes a side effect nobody asked for: with the derived
     * rule alone, delegating from a member of team X to a member of team Y
     * moves visibility <em>off</em> X. A declared team does not move.
     *
     * <p><b>It widens, it does not confine.</b> The derived assignee-team rule
     * keeps applying alongside it, so after a delegation into team Y both X and
     * Y can see the thread. That is not an oversight and cannot be tightened
     * here: whoever may decide must be able to read, {@code mayDecide} admits
     * the assignee's team by design, and {@code maySee} ⊇ {@code mayDecide} is
     * the invariant that keeps a decision from being made blind. If a thread
     * must not be visible to the new assignee's colleagues, the answer is not
     * to delegate it there. See {@code InboxAuthz}.
     */
    private @Nullable String teamId;

    /**
     * Who receives updates on this thread — and, as a property of the object
     * rather than a permission, who may read it. Grows by contributing, by
     * being invited and by delegation; shrinks by unsubscribing. Deliberately
     * <b>not</b> derived from originator/assignee/authors: an invited person
     * appears in none of those, and unsubscribing would be impossible because
     * whoever wrote once stays an author forever.
     */
    @Builder.Default
    private List<String> participants = new ArrayList<>();

    /**
     * Who has read title and body. The thread is the root node of its own
     * tree, so it carries a read state like every message does — and without
     * this a freshly created thread (which has no messages yet) could never
     * be unread.
     */
    @Builder.Default
    private List<String> readBy = new ArrayList<>();

    /**
     * Badge index: everyone with something unopened anywhere in this thread.
     * Always a subset of {@link #participants}.
     *
     * <p>Derivable — {@code {u : u ∉ readBy} ∪ {u : ∃ message with u ∉
     * message.readBy}} — but precomputed, because the badge count cannot
     * derive it without scanning: the alternative,
     * {@code messages: {$elemMatch: {readBy: {$ne: me}}}}, is a negative
     * match and not index-bounded. Kept in step inside the same update as the
     * truth it indexes, and rebuildable from the messages if it ever drifts.
     */
    @Builder.Default
    private List<String> unreadFor = new ArrayList<>();

    /** Reactions on the thread itself, i.e. on title and body. */
    @Builder.Default
    private List<MaximegalonReaction> reactions = new ArrayList<>();

    /**
     * The clarification, as a flat array with {@code parentId} links —
     * embedded rather than a second collection so that appending a message
     * and updating {@link #unreadFor} is <em>one</em> atomic update instead of
     * two writes that can drift apart. Bounded (see
     * {@code MaximegalonService.MAX_MESSAGES}), because an unbounded embedded
     * array walks into the 16 MB document limit, and a document that has burst
     * it is neither readable nor repairable through the normal API.
     *
     * <p><b>Excluded from list queries.</b> {@code list()} returns whole
     * documents; without a projection every inbox listing would drag every
     * discussion along.
     */
    @Builder.Default
    private List<MaximegalonMessage> messages = new ArrayList<>();

    @Builder.Default
    private List<MaximegalonHistoryEntry> history = new ArrayList<>();

    @Version
    private @Nullable Long version;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;

    private @Nullable Instant archivedAt;
}
