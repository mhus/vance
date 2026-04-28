package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * direct {@code engine}-spawns. Audit only — engines do not act
     * on this field.
     */
    private @Nullable String recipeName;

    /**
     * Recipe-derived system-prompt fragment. Engines blend this with
     * their built-in prompt according to {@link #promptMode}.
     * {@code null} means "no recipe override".
     */
    private @Nullable String promptOverride;

    /**
     * Recipe's small-model variant of {@link #promptOverride}. The
     * engine picks this when the resolved AI model has
     * {@code ModelSize.SMALL}; otherwise it falls back to
     * {@link #promptOverride}. {@code null} means "use the
     * default-size prompt for all models" — the recipe shipped just
     * one variant.
     */
    private @Nullable String promptOverrideSmall;

    @Builder.Default
    private PromptMode promptMode = PromptMode.APPEND;

    /**
     * Recipe's override for the engine's "intent-without-action"
     * validator message. {@code null} keeps the engine's hardcoded
     * default.
     */
    private @Nullable String intentCorrectionOverride;

    /**
     * Recipe's override for the data-relay-gap validator message.
     * {@code null} keeps the engine's hardcoded default.
     */
    private @Nullable String dataRelayCorrectionOverride;

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

    private ThinkProcessStatus status = ThinkProcessStatus.READY;

    @Version
    private @Nullable Long version;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;
}
