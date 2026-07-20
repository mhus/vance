package de.mhus.vance.shared.chat;

import de.mhus.vance.api.chat.ChatRole;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent chat message.
 *
 * <p>{@code sessionId} is the {@code SessionDocument.sessionId} (business
 * id); {@code thinkProcessId} is the Mongo id of the owning
 * {@code ThinkProcessDocument}. The combined index
 * {@code (tenantId, sessionId, thinkProcessId, createdAt)} supports the
 * hot-path query: "give me the history of process X in order".
 *
 * <p>{@code tags} hold marker annotations set by the tool dispatcher
 * and engines (e.g. {@code FILE_EDIT}, {@code TOOL_CALL:client_file_edit},
 * {@code RESOURCE:CLIENT_FILE:/abs/path}, {@code PLAN_STEP_STARTED:cleanup}).
 * They drive the {@code history_search} tool that lets the LLM look up
 * past turns — including ones already rolled into a compaction memory.
 * See {@code planning/process-history-search.md}.
 */
@Document(collection = "chat_messages")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_session_process_time_idx",
                def = "{ 'tenantId': 1, 'sessionId': 1, 'thinkProcessId': 1, 'createdAt': 1 }"),
        @CompoundIndex(
                name = "tenant_process_tags_time_idx",
                def = "{ 'tenantId': 1, 'thinkProcessId': 1, 'tags': 1, 'createdAt': -1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDocument {

    /**
     * {@link #meta} key for the structured ASK_USER picker options.
     * Value is {@code List<Map<String, Object>>} with at least
     * {@code label} and optional {@code description} per entry. See
     * {@code specification/eddie-engine.md} §5.6 / §5.8.
     */
    public static final String META_ASK_USER_OPTIONS = "askUserOptions";

    /**
     * {@link #meta} key for the engine-action type that produced the
     * chat message — typically one of {@code ANSWER}, {@code ASK_USER},
     * {@code REJECT}, {@code WAIT}, {@code RELAY} (engine-specific).
     * Web-UI dispatches its rendering on this — see
     * {@code specification/inline-and-embedded-content.md} §11
     * (chat-message dispatch). Absent on messages that didn't pass
     * through an action handler (e.g. the LLM fell back to raw text,
     * USER-originated turns, system notices); UI falls back to the
     * default Markdown render in that case.
     */
    public static final String META_ACTION_TYPE = "actionType";

    /** Action-type value: free-form answer. */
    public static final String ACTION_TYPE_ANSWER = "ANSWER";
    /** Action-type value: clarification question (optionally with picker options). */
    public static final String ACTION_TYPE_ASK_USER = "ASK_USER";
    /** Action-type value: out-of-scope refusal. */
    public static final String ACTION_TYPE_REJECT = "REJECT";
    /** Action-type value: async work in flight, ephemeral notice. */
    public static final String ACTION_TYPE_WAIT = "WAIT";
    /** Action-type value: relayed worker reply. */
    public static final String ACTION_TYPE_RELAY = "RELAY";

    /**
     * {@link #meta} key, Boolean. When {@code true}, the
     * {@code PrakPeriodicListener} skips the side-channel analyser for
     * this message — set by engines when every tool invoked in the
     * producing turn has {@code Tool.contributesPrak() == false} (todo
     * tracking, find_tools, manual_read, etc.). Absent / {@code false}
     * leaves the listener's normal cheap-path filter in charge.
     */
    public static final String META_PRAK_SKIP = "prakSkip";

    /**
     * {@link #meta} key, {@code List<String>}. Union of
     * {@code Tool.prakLabels()} across every tool invoked in the turn
     * that produced this message. {@code PrakSideChannelRunner} picks
     * these up and merges them into the labels of every insight
     * extracted from the span — so memory searches like "what do we
     * know about IMAP" find both analyser-emitted and tool-emitted
     * domain labels without prompt-engineering per tool family.
     */
    public static final String META_PRAK_TOOL_LABELS = "prakToolLabels";

    /**
     * {@link #meta} key, String. Classifies the role this message plays
     * within a turn. Absent on canonical messages (the default — final
     * USER/ASSISTANT/SYSTEM payloads, the canonical reply at natural-stop).
     *
     * <p>Known values:
     * <ul>
     *   <li>{@link #KIND_INTERIM} — an intermediate working-log emitted
     *       during a multi-iteration engine loop (e.g. Frankie narrates
     *       between tool batches). Live-streamed to clients for progress
     *       visibility, but filtered out of every LLM-replay /
     *       compaction / Prak / RAG path. Only the canonical message at
     *       turn-end is considered authoritative content.</li>
     * </ul>
     *
     * <p>Engines that don't emit interims simply never set this key.
     */
    public static final String META_KIND = "kind";

    /** {@link #META_KIND} value for intermediate working-log messages — see {@link #META_KIND}. */
    public static final String KIND_INTERIM = "interim";

    /**
     * {@link #META_KIND} value for a message the user explicitly removed
     * from the session's memory via Modify/Crop. The message stays in the
     * database (audit-readable via {@code history()}), but is excluded from
     * every LLM-replay / compaction / recompaction / history-search path
     * and from the chat scrollback — as if it had never been said, without
     * destroying it. Reversible: clearing the marker restores the message.
     * See {@code specification/public/session-crop.md}.
     */
    public static final String KIND_REMOVED = "removed";

    /**
     * Convenience: returns {@code true} when {@link #meta} marks this
     * message as an intermediate working-log (see {@link #KIND_INTERIM}).
     * Centralised so callers don't repeat the key/value check.
     */
    public boolean isInterim() {
        return KIND_INTERIM.equals(meta.get(META_KIND));
    }

    /**
     * Convenience: returns {@code true} when the user removed this message
     * from memory via Modify/Crop (see {@link #KIND_REMOVED}).
     */
    public boolean isRemoved() {
        return KIND_REMOVED.equals(meta.get(META_KIND));
    }

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String sessionId = "";

    /** Owning think-process — {@code ThinkProcessDocument.id} (Mongo id). */
    private String thinkProcessId = "";

    private ChatRole role = ChatRole.USER;

    @TextIndexed
    private String content = "";

    /**
     * The model's reasoning ("thinking") text for ASSISTANT messages —
     * the {@code <think>…</think>} / Harmony-{@code analysis} markup that
     * the sanitizer strips out of {@link #content}, captured per turn so
     * the user can review the model's train of thought (rendered as a
     * collapsible section in the chat UI). {@code null} for models
     * without reasoning markup, for non-ASSISTANT roles, and for rows
     * written before this field existed. Not text-indexed — internal
     * monologue is deliberately excluded from chat search.
     */
    private @Nullable String thinking;

    /**
     * Set when the message has been rolled into a memory compaction
     * ({@code MemoryDocument.id}). Replay paths skip these so the LLM
     * sees the compacted summary instead of the originals; the
     * originals stay in Mongo, audit-readable.
     */
    private @Nullable String archivedInMemoryId;

    /**
     * Marker tags for LLM-accessible history search. Append-only —
     * engines/tools add typed markers for classification, never remove.
     * Convention: {@code TYPE} or {@code TYPE:VALUE}. Examples:
     * {@code TOOL_CALL:client_file_edit}, {@code RESOURCE:CLIENT_FILE:/abs/path},
     * {@code FILE_EDIT}, {@code PLAN_STEP_STARTED:cleanup}, {@code ERROR}.
     *
     * <p>{@link LinkedHashSet} for stable iteration order in debug
     * output; the performance difference vs. {@link java.util.HashSet}
     * is irrelevant at this size.
     */
    @Builder.Default
    private Set<String> tags = new LinkedHashSet<>();

    /**
     * Optional structured metadata that travels alongside the message
     * content. Used today for {@code ASK_USER} actions to carry the
     * structured {@code options} picker — Markdown rendering of the
     * options lives in {@code content}, the typed array lives here so
     * picker-aware clients (Web-UI) and the cross-engine relay path
     * (see {@code specification/eddie-engine.md} §5.8) can read the
     * structured form without parsing markdown.
     *
     * <p>Conventional keys (additive — unknown keys are tolerated and
     * passed through):
     * <ul>
     *   <li>{@code askUserOptions} — {@code List<Map<String,Object>>}
     *       with at least {@code label}, optionally {@code description}
     *       per entry. Set by ASK_USER action handlers that received a
     *       non-empty {@code options} param.</li>
     * </ul>
     */
    @Builder.Default
    private Map<String, Object> meta = new LinkedHashMap<>();

    /**
     * User-id of the sender for USER-role messages — see
     * {@code planning/multi-user-sessions.md} §3.5.
     *
     * <p>In single-user sessions this is implicit (the session's
     * {@code userId}); in multi-user sessions multiple distinct users
     * write into the same conversation, so we persist the sender per
     * turn. {@code null} for non-USER roles (ASSISTANT, SYSTEM) and
     * for legacy rows created before this field existed — callers
     * fall back to the session's {@code userId} when null.
     */
    private @Nullable String senderUserId;

    /**
     * Display-name of the sender at the time the message was written
     * — see {@code planning/multi-user-sessions.md} §3.5. Captured for
     * the LLM prompt render (turns are prefixed with {@code "Alice: "}
     * etc. so the agent can tell speakers apart) and for the chat-UI
     * to avoid an extra lookup per turn.
     *
     * <p>{@code null} for non-USER roles and for legacy rows.
     */
    private @Nullable String senderDisplayName;

    /**
     * {@code true} when this USER turn explicitly addressed the agent
     * (contained an {@code @ai} / {@code @vance} / {@code @<engine>}
     * mention) or the session was not in collaboration-mode at receive
     * time — see {@code planning/multi-user-sessions.md} §3.2 / §4.
     *
     * <p>When {@code false} the turn is a background message: persisted
     * for context but did not wake the agent. Beim Drain sieht der
     * Agent solche Turns als reinen Kontext.
     *
     * <p>Default {@code true} keeps backward compatibility — legacy
     * 1:1 turns and all ASSISTANT/SYSTEM turns are always "addressed".
     */
    @Builder.Default
    private boolean addressedToAgent = true;

    @CreatedDate
    private @Nullable Instant createdAt;
}
