package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Owning project ({@code ProjectDocument.name}). Denormalised from
     * the session at spawn time so engines don't need a session lookup
     * to drive project-aware settings cascades. Empty string means
     * "unknown project" — the cascade then falls through to
     * {@code _vance} only.
     */
    private String projectId = "";

    /** Owning session — {@code SessionDocument.sessionId}. */
    private String sessionId = "";

    /** Unique within the session, e.g. {@code "chat"} or a generated worker name. */
    private String name = "";

    /** Optional display name for UI. */
    private @Nullable String title;

    /** Engine name from the registry, e.g. {@code "ford"}, {@code "arthur"}, {@code "deep-think"}. */
    private String thinkEngine = "";

    /** Engine version at creation time — for resume compatibility checks. */
    private @Nullable String thinkEngineVersion;

    /** Optional goal for batch-style engines; reactive engines leave this null. */
    private @Nullable String goal;

    /**
     * Engine-specific runtime parameters set at create-time.
     * Examples: {@code "model": "claude-haiku-4-5"},
     * {@code "validation": true}, {@code "maxIterations": 8}.
     *
     * <p>Schema is engine-defined — each engine reads only the keys
     * it knows about and ignores the rest. Default empty map so an
     * engine that doesn't declare any params still gets a non-null
     * collection at runtime.
     */
    @Builder.Default
    private Map<String, Object> engineParams = new LinkedHashMap<>();

    /**
     * Recipe name this process was spawned from, or {@code null} for
     * direct {@code engine}-spawns. Primarily for audit + UI
     * attribution; most engines do not branch on it. Hactar
     * reads it as a fallback to derive a reviewer sub-recipe name
     * ({@code <recipeName>-reviewer}) when {@code reviewerRecipe} is
     * not explicitly set in params — see
     * {@code planning/hactar-engine.md}.
     */
    private @Nullable String recipeName;

    /**
     * Connection-profile that was active when this process was spawned
     * (e.g. {@code "foot"}, {@code "web"}, {@code "mobile"}). {@code null}
     * for processes spawned outside an interactive connection (e.g. by
     * Eddie tools acting on the user's behalf). Audit only — engines do
     * not act on this field; the recipe-time profile-block merge has
     * already been folded into {@link #engineParams},
     * {@link #promptOverride}, and {@link #allowedToolsOverride}.
     */
    private @Nullable String connectionProfile;

    /**
     * Recipe-derived system-prompt fragment, optionally Pebble-
     * templated. Engines blend the rendered text with their built-in
     * prompt according to {@link #promptMode}. {@code null} means
     * "no recipe override".
     *
     * <p>Tier/model/mode/profile-aware variation is expressed inside
     * the template body via {@code {% if tier == "small" %}…{% endif %}}
     * and similar — the field is a single source of truth, no longer
     * paired with a {@code promptOverrideSmall} variant.
     */
    private @Nullable String promptOverride;

    /**
     * Profile-block's {@code promptPrefixAppend} carried separately
     * from {@link #promptOverride} so the recipe template can splice
     * it in at any position via {@code {{ profileAppend }}}. When the
     * recipe template doesn't reference the variable, the renderer
     * falls back to legacy auto-append at the end of
     * {@link #promptOverride}. {@code null} when the active profile-
     * block carries no append. See {@code planning/prompt-inlining.md}.
     */
    private @Nullable String promptOverrideAppend;

    @Builder.Default
    private PromptMode promptMode = PromptMode.APPEND;

    /**
     * Recipe's override for the data-relay-gap validator message.
     * {@code null} keeps the engine's hardcoded default.
     */
    private @Nullable String dataRelayCorrectionOverride;

    /**
     * Routing pointer for chat-orchestrator engines (Arthur, Eddie):
     * the id of the child worker this process is currently asking the
     * user a clarification on behalf of. While set, raw user-chat
     * input that arrives at this process is auto-forwarded to the
     * pointed worker — no LLM round-trip — so the orchestrator
     * doesn't have to remember worker names across turns and the
     * user's answer can never reach a hallucinated wrong target.
     *
     * <p>Lifecycle:
     * <ul>
     *   <li>Set when this process drains exactly one BLOCKED
     *       {@code ProcessEvent} from a child and ends the turn with
     *       {@code awaiting_user_input=true}.</li>
     *   <li>Cleared when any non-trivial drain happens (a new
     *       {@code ProcessEvent}, an LLM-mediated turn) so a fresh
     *       LLM-driven decision can take over.</li>
     * </ul>
     * Only chat-orchestrator engines populate this; workers leave it
     * {@code null}.
     */
    private @Nullable String activeDelegationWorkerId;

    /**
     * Effective allowed-tools set computed from the engine's default
     * plus the recipe's add/remove lists at spawn time. {@code null}
     * means "no override — use the engine default", which is the
     * normal case for processes that weren't spawned from a recipe.
     * An empty <em>non-null</em> set is intentionally restrictive
     * ("this process may invoke no tools").
     */
    private @Nullable Set<String> allowedToolsOverride;

    /**
     * Snapshot of the recipe's {@code allowedSkills} whitelist at spawn
     * time. {@code null} means "no restriction" (any visible skill may
     * be activated via trigger / {@code /skill}); an empty
     * <em>non-null</em> set is a hard lockdown ("no skill may ever be
     * active on this process"). Mirrors the
     * {@link #allowedToolsOverride} pattern.
     *
     * <p>Recipe edits after spawn do not affect a running process — the
     * snapshot wins. {@link #activeSkills} entries with
     * {@code fromRecipe=true} that came from {@code defaultActiveSkills}
     * are not subject to this filter (they were placed by the recipe
     * author themselves at spawn time).
     */
    private @Nullable Set<String> allowedSkillsOverride;

    /**
     * Mongo id of the orchestrator process that spawned this one.
     * {@code null} for top-level processes (e.g. the session's chat).
     * Used to route life-cycle {@code ProcessEvent}s back to the parent
     * via the pending queue + Auto-Wakeup.
     */
    private @Nullable String parentProcessId;

    /**
     * Skills currently active on this process. Activations come from
     * three sources: the spawning recipe ({@code fromRecipe=true}),
     * Arthur's auto-trigger detection (implicit), and explicit user
     * commands via {@code /skill <name>}. Sticky by default;
     * {@code oneShot} entries are removed after the next lane-turn.
     *
     * <p>See {@code specification/skills.md}.
     */
    @Builder.Default
    private List<ActiveSkillRefEmbedded> activeSkills = new ArrayList<>();

    private ThinkProcessStatus status = ThinkProcessStatus.INIT;

    /**
     * Operating mode for chat-orchestrator engines (Arthur). Drives the
     * tool filter (read-only in {@code EXPLORING}/{@code PLANNING}) and
     * the system-prompt variant. Non-Arthur engines leave this at
     * {@link ProcessMode#NORMAL} for the entire process life.
     *
     * <p>See {@code readme/arthur-plan-mode.md} §3.1.
     */
    @Builder.Default
    private ProcessMode mode = ProcessMode.NORMAL;

    /**
     * TodoList of plan steps, owned by Arthur in {@code PLANNING}/
     * {@code EXECUTING}-mode. Set fresh on every {@code PROPOSE_PLAN};
     * status updates flow via {@code TODO_UPDATE}-actions during
     * execution. Empty for non-Arthur engines and for Arthur in
     * {@link ProcessMode#NORMAL}/{@link ProcessMode#EXPLORING}.
     *
     * <p>See {@code planning/arthur-plan-mode.md} §3.2.
     */
    @Builder.Default
    private List<TodoItem> todos = new ArrayList<>();

    /**
     * Connection-profile of the WS currently bound to this process's
     * session — propagated from the {@code session-bind} / {@code engine-bind}
     * handshake (see {@code engine-message-routing.md} §4.1.1). Drives
     * the per-turn tool filter via {@code Tool.allowedForProfile()}.
     *
     * <p>Canonical values: {@code "foot"}, {@code "web"}, {@code "mobile"},
     * {@code "eddie"}, {@code "daemon"}. {@code null} = no profile
     * recorded; tool filter runs in legacy unrestricted mode.
     */
    private @Nullable String boundProfile;

    /**
     * Eddie-only: per-worker mirror entries — one per active Working WS
     * Eddie holds to a worker {@code ThinkProcess} in another project.
     * Holds connection identity (for reconnect after pod reassignment),
     * the chosen {@code ChannelMode}, the last seen plan snapshot, and
     * triage working-memory.
     *
     * <p>See {@code specification/eddie-engine.md} §8 +
     * {@code planning/eddie-moderator-erweiterung.md} +
     * {@code planning/eddie-plan-mode.md}. Empty for non-Eddie engines.
     */
    @Builder.Default
    private List<de.mhus.vance.shared.eddie.WorkerLinkSnapshot> workerLinks = new ArrayList<>();

    /**
     * Eddie-only: the foreign project Eddie currently coordinates with
     * (her "spot"). Set by {@code SWITCH_PROJECT}, by
     * {@code DELEGATE_PROJECT} as a side-effect, or via the
     * {@code /project} slash-command. {@link #projectId} (Eddie's home)
     * is unaffected — only the spot moves.
     *
     * <p>References a {@code ProjectDocument.name}, not a Mongo id. Empty
     * string is normalised to {@code null} by the service helpers.
     *
     * <p>Spot-bound tools (e.g. {@code STEER_PROJECT},
     * {@code project_chat_send}) read this via
     * {@link de.mhus.vance.brain.thinkengine.ThinkEngineContext#requireWorkingProjectId()};
     * when it is {@code null} those tools fail fast instead of letting
     * the LLM hallucinate a project name. Home-bound tools
     * ({@code doc_*}, {@code scratch_*}) continue to use {@link #projectId}.
     *
     * <p>Always {@code null} for non-Eddie engines.
     */
    private @Nullable String workingProjectId;

    /**
     * Tools that the LLM has activated by calling {@code describe_tool}
     * on a {@link de.mhus.vance.toolpack.Tool#deferred()}-marked tool.
     * Map value is the activation timestamp (refreshed on every
     * subsequent invocation through {@code ContextToolsApi}); the
     * configured TTL ({@code vance.tooling.deferralActivationTtl},
     * default 15 min) decays stale entries lazily.
     *
     * <p>Empty for processes whose engines never expose deferred
     * tools or whose recipe pins everything to primary.
     *
     * <p>See {@code planning/tool-schema-deferral.md} §4.3 / §6.
     */
    @Builder.Default
    private Map<String, Instant> activatedDeferredTools = new LinkedHashMap<>();

    /**
     * Set when {@link #status} is {@link ThinkProcessStatus#CLOSED}, null
     * otherwise. Audit/UI metadata: {@code DONE} (goal reached),
     * {@code STOPPED} (user/parent/cascade), {@code STALE} (inconsistent).
     * Drives no behaviour.
     */
    private @Nullable CloseReason closeReason;

    /**
     * LRU cache of resources the process has already read into context.
     * Ordered oldest-first; when the configured bound is exceeded, the
     * head is dropped. Keys use the same typed-resource namespace as
     * the history-search markers
     * ({@code CLIENT_FILE:/abs/path}, {@code WORKSPACE:<proc>/<rel>},
     * {@code DOCUMENT:<id>}, {@code MEMORY:<id>}).
     *
     * <p>Drives read-dedup at prompt-assembly time: a re-injection of
     * the same {@code (key, contentHash)} pair is skipped. See
     * {@code planning/brain-context-assembler.md} §3 + §4.
     *
     * <p>Volatile — kein audit-relevantes Datum. Darf jederzeit
     * geclearert werden ohne Verlust.
     */
    @Builder.Default
    private List<ReadStateEntry> readState = new ArrayList<>();

    /**
     * One-shot injection markers — once a key is in this set, the
     * corresponding auto-attachment is never re-injected for the
     * lifetime of the process. Used for things that semantically
     * belong injected exactly once (CLAUDE.md, Memory-Pin,
     * Kit-Welcome). Unlike {@link #readState}, no LRU eviction —
     * append-only.
     *
     * <p>See {@code planning/brain-context-assembler.md} §3.
     */
    @Builder.Default
    private Set<String> shownOnce = new LinkedHashSet<>();

    /**
     * Out-of-band signal for engines that drive a drain-loop inside
     * their own {@code runTurn} (e.g. Arthur). Set immediately by
     * {@code /pause} / {@code /stop} BEFORE the queued
     * status-transition task fires on the lane — the engine polls
     * this flag between iterations of its drain-loop and bails out
     * early so the lane frees up for the queued pause-task. Without
     * this, an engine that keeps draining new pending messages
     * (auto-wakeup) would never let the pause-task run.
     */
    private boolean haltRequested = false;

    /**
     * Timestamp of the last successful Prak side-channel pass over this
     * process. {@code null} means Prak has never seen any message yet —
     * the periodic trigger then considers the entire active history as
     * "unrated". Updated by
     * {@code de.mhus.vance.brain.prak.PrakPeriodicTrigger} after each
     * successful run.
     *
     * <p>Not part of the audit-trail of the process — this is just a
     * cursor for the periodic trigger. The audit chain lives in
     * {@code prak_runs}.
     */
    private @Nullable Instant lastPrakAt;

    @Version
    private @Nullable Long version;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;
}
