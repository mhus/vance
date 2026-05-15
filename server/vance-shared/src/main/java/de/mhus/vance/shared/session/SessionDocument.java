package de.mhus.vance.shared.session;

import de.mhus.vance.api.session.DisconnectPolicy;
import de.mhus.vance.api.session.IdlePolicy;
import de.mhus.vance.api.session.SessionColor;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendCause;
import de.mhus.vance.api.session.SuspendPolicy;
import de.mhus.vance.api.ws.Profiles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent session record.
 *
 * <p>A session is created on first handshake and outlives the WebSocket
 * connection — the client may disconnect, another pod may pick it up
 * later. {@code boundConnectionId} marks which connection currently
 * holds the session; {@code null} means no one is connected (resumable).
 * {@code lastActivityAt} is touched on every inbound frame (atomically)
 * so a later cleanup job can time out idle sessions.
 *
 * <p>Pod-affinity has moved to the project level — see
 * {@link de.mhus.vance.shared.project.ProjectDocument#getHomeCluster()}. A
 * session implicitly inherits the affinity of its project, so the
 * session itself only tracks the connection that owns it, not the pod.
 *
 * <p>Exactly one connection may be bound at a time —
 * {@link SessionService#tryBind(String, String)} enforces this with a
 * conditional {@code updateFirst}.
 *
 * <p>User-facing metadata ({@link #title}, {@link #icon}, {@link #color},
 * {@link #tags}, {@link #pinned}) is separate from
 * {@link #displayName} — the latter carries the user's identity-derived
 * name (set from the connection context at create time and used by
 * engines like Eddie for the prompt salutation); the former is the
 * session's editable title and is set by the user or the LLM
 * auto-suggester. See {@code specification/session-lifecycle.md} §14.
 */
@Document(collection = "sessions")
@CompoundIndexes({
        @CompoundIndex(name = "sessionId_idx", def = "{ 'sessionId': 1 }", unique = true),
        @CompoundIndex(name = "tenant_user_idx", def = "{ 'tenantId': 1, 'userId': 1 }"),
        @CompoundIndex(name = "tenant_user_project_idx",
                def = "{ 'tenantId': 1, 'userId': 1, 'projectId': 1 }"),
        @CompoundIndex(name = "status_activity_idx", def = "{ 'status': 1, 'lastActivityAt': 1 }"),
        // Suspend-sweep query: status=SUSPENDED + transitionAt <= now.
        @CompoundIndex(name = "status_transition_idx",
                def = "{ 'status': 1, 'transitionAt': 1 }"),
        // Default list ordering: pinned first, then most recent.
        @CompoundIndex(name = "pinned_activity_idx",
                def = "{ 'pinned': -1, 'lastActivityAt': -1 }"),
        // Tag-filter on the session list view.
        @CompoundIndex(name = "tags_idx", def = "{ 'tags': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionDocument {

    @Id
    private @Nullable String id;

    /** Business id exposed to clients — {@code sess_...}. */
    private String sessionId = "";

    private String tenantId = "";

    /** Owning user — {@code UserDocument.name}. */
    private String userId = "";

    /** Project the session operates in — {@code ProjectDocument.name}. */
    private String projectId = "";

    /**
     * Identity-derived label captured at session-create from the
     * connection context (typically the user's display name). Used
     * by engines (e.g. Eddie) for prompt salutations and as a
     * fallback label in older UIs. The session's editable title is
     * {@link #title} — not this field.
     */
    private @Nullable String displayName;

    /** Connection profile of the client that created this session — open string. */
    private String profile = Profiles.WEB;

    private String clientVersion = "";

    /** Optional client-supplied identifier (logs / UI). */
    private @Nullable String clientName;

    /**
     * Optional path of the client-uploaded agent doc (e.g. {@code "./agent.md"}).
     * Used as display in the prompt heading. {@code null} when no upload
     * happened in this session.
     */
    private @Nullable String clientAgentDocPath;

    /**
     * Optional client-uploaded agent doc body (Markdown). Pushed by the
     * client right after session-bind via {@code client-agent-upload}.
     * Spliced into the conversation memory block when the active recipe's
     * profile-block opts in via {@code params.useClientAgentDoc=true}.
     * {@code null} until first upload; remains stable across reconnects
     * unless the client sends a fresh upload (e.g. via foot's
     * {@code /reload}).
     */
    private @Nullable String clientAgentDoc;

    /** UUID generated by the pod that currently holds the connection, or {@code null} when idle. */
    private @Nullable String boundConnectionId;

    private SessionStatus status = SessionStatus.INIT;

    // ─── Lifecycle policy (immutable after create) — see specification/session-lifecycle.md §5 ───

    /** What happens on client-disconnect. Resolved from the bootstrap-recipe's profile/session block. */
    private DisconnectPolicy onDisconnect = DisconnectPolicy.KEEP_OPEN;

    /** What happens when all engines are idle for {@link #idleTimeoutMs}. */
    private IdlePolicy onIdle = IdlePolicy.NONE;

    /**
     * What happens after a non-FORCED suspend. {@code KEEP} →
     * {@code ARCHIVED} once {@code transitionAt} passes; {@code CLOSE}
     * → {@code CLOSED}.
     */
    private SuspendPolicy onSuspend = SuspendPolicy.KEEP;

    /** Idle threshold in milliseconds (used when {@link #onIdle} == SUSPEND). */
    private long idleTimeoutMs = 1_800_000L; // 30 min

    /** Time spent in SUSPENDED before the sweeper transitions to ARCHIVED/CLOSED (non-FORCED causes). */
    private long suspendKeepDurationMs = 86_400_000L; // 24 h

    // ─── Suspend runtime state (mutable; null while OPEN/INIT) — see §9 ───

    /** When the session entered SUSPENDED. Null while non-SUSPENDED. */
    private @Nullable Instant suspendedAt;

    /** Why the session entered SUSPENDED. Null while non-SUSPENDED. */
    private @Nullable SuspendCause suspendCause;

    /**
     * When the suspend-sweeper transitions this session to its next
     * status — either {@code ARCHIVED} (onSuspend=KEEP) or
     * {@code CLOSED} (onSuspend=CLOSE). Stamped at suspend-time. Null
     * while non-SUSPENDED. Mutable — admin/UI may push it forward to
     * extend the keep-window without resuming.
     */
    private @Nullable Instant transitionAt;

    /**
     * Mongo id of the auto-spawned session-chat think-process —
     * exists 1:1 with the session for its lifetime, engine taken
     * from the {@code session.defaultChatEngine} setting at
     * create-time. {@code null} only between session-create and
     * the first {@code SessionChatBootstrapper.ensureChatProcess}
     * call (or for sessions created before the bootstrapper
     * existed). Driven through {@link SessionService#setChatProcessId}
     * so a race can't link two chat-processes.
     */
    private @Nullable String chatProcessId;

    private Instant createdAt = Instant.EPOCH;

    /**
     * Last time anything happened on this session — updated atomically on every
     * inbound frame / heartbeat. Drives the idle-timeout cleanup job.
     */
    private Instant lastActivityAt = Instant.EPOCH;

    /**
     * First USER-role chat message in this session, truncated to ≤250
     * characters. Set once on the first user message and never
     * overwritten — this is the session's stable "topic". Denormalised
     * by {@code ChatMessageService.append} via
     * {@link SessionService#touchChatPreview}.
     */
    private @Nullable String firstUserMessage;

    /**
     * Most recent chat message in this session, truncated to ≤250
     * characters. Updated atomically on every chat append. Combined
     * with {@link #lastMessageRole} and {@link #lastMessageAt} this
     * lets the inspector / session list show a "what happened last"
     * preview without re-fetching the whole chat.
     */
    private @Nullable String lastMessagePreview;

    /** Role of the message captured in {@link #lastMessagePreview}. */
    private @Nullable String lastMessageRole;

    /** When the {@link #lastMessagePreview} message was created. */
    private @Nullable Instant lastMessageAt;

    // ─── User-facing metadata — see specification/session-lifecycle.md §14 ───

    /**
     * Editable session title. Either set by the user or filled by the
     * LLM auto-suggester after the first Q&amp;A pair. UI fallback chain
     * for display: {@code title} → {@code firstUserMessage} → "Untitled".
     */
    @TextIndexed(weight = 10)
    private @Nullable String title;

    /**
     * {@code true} while {@link #title} originates from the auto-suggester
     * and the user has not edited it. Flips to {@code false} on the first
     * user edit; the auto-suggester never touches the field again.
     */
    private boolean titleAutoGenerated;

    /**
     * Unicode emoji codepoint or ZWJ sequence (≤ 8 chars). Portable
     * across web / mobile / CLI; no font-icon-set versioning. {@code null}
     * means UI falls back to a status-derived glyph.
     */
    private @Nullable String icon;

    /**
     * Accent color from the restricted 12-value palette. {@code null}
     * means no color set; UI renders neutral.
     */
    private @Nullable SessionColor color;

    /**
     * User-supplied tags. Stored normalised (lowercase, trimmed, deduped,
     * ≤ 20 entries, each ≤ 50 chars). Empty when untagged.
     */
    @TextIndexed(weight = 5)
    private List<String> tags = new ArrayList<>();

    /**
     * {@code true} when the user pinned the session — pinned entries
     * float above the rest in the default list order
     * ({@code pinned: -1, lastActivityAt: -1}).
     */
    private boolean pinned;

    /**
     * Stamped the first time a user-driven action modifies this
     * session's metadata or lifecycle properties (manual archive,
     * manual title edit, tagging, pin, etc.). Used by the
     * abandoned-detection predicate to distinguish "user invested
     * in this session" from "system-set defaults only". Never reset.
     */
    private @Nullable Instant userTouchedAt;

    // ─── Archive runtime state — see §11 ───

    /**
     * When the session entered {@link SessionStatus#ARCHIVED}. Stamped
     * by the archive cascade, cleared by reactivate.
     */
    private @Nullable Instant archivedAt;

    /**
     * Last time the session was reactivated out of {@code ARCHIVED}.
     * Audit-only — does not drive lifecycle behaviour. Survives further
     * archive/reactivate cycles (overwritten on each reactivate).
     */
    private @Nullable Instant reactivatedAt;

    /**
     * {@code true} for sessions owned by a system-level component
     * (e.g. a scheduler — see {@code specification/scheduler.md} §6).
     * System sessions are hidden from the default Web-UI session list,
     * skip auto-titling, and do not count as "user-touched". The flag
     * is set at create-time and never changes.
     */
    private boolean system;
}
