package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.session.SessionLifecycleConfig;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Per-Connection-Profile override block on a recipe. Applied on top of the
 * recipe-base values during {@link RecipeResolver#apply}. See
 * {@code specification/recipes.md} §6a and
 * {@code specification/session-lifecycle.md} §6 for the schema and the
 * merge rules.
 *
 * <p>Lookup cascade: exact-match key → {@code default} key → empty (no overlay).
 * The {@code default} key is the only profile-block name with reserved
 * semantics; everything else is open string and configurable per tenant.
 *
 * <p>{@code session} carries the per-profile {@link SessionLifecycleConfig}
 * (onDisconnect, onIdle, onSuspend, idleTimeoutMs, suspendKeepDurationMs).
 * Only consumed when this is the bootstrap-recipe of a {@code session-create}
 * — worker spawns ignore it.
 *
 * <p>{@code allowedToolsDefer} demotes tools to the deferred bucket
 * (LLM sees them only via the discovery block + {@code describe_tool}
 * activation). {@code modes} carries per-mode overlays — keyed by
 * the engine's mode names (Arthur: {@code EXPLORING}, {@code PLANNING},
 * {@code EXECUTING}, {@code NORMAL}; the literal {@code default} key
 * is the catch-all). See {@code planning/tool-schema-deferral.md} §14.
 *
 * <p>{@code manuals} from the spec is intentionally not parsed yet — the
 * doc/manual consolidation is still open ({@code instructions/_todo.md}).
 * When manuals are wired, the field is added here without breaking the
 * recipe schema.
 */
public record ProfileBlock(
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        List<String> allowedToolsDefer,
        Map<String, RecipeModeBlock> modes,
        @Nullable String promptPrefixAppend,
        Map<String, Object> params,
        @Nullable SessionLifecycleConfig sessionLifecycleConfig) {

    /** Reusable empty block for profiles that have no overlay. */
    public static final ProfileBlock EMPTY =
            new ProfileBlock(List.of(), List.of(), List.of(), Map.of(), null, Map.of(), null);

    /** {@code true} when neither tool-list nor any mode-block carries an entry. */
    public boolean hasToolFilter() {
        return (allowedToolsAdd != null && !allowedToolsAdd.isEmpty())
                || (allowedToolsRemove != null && !allowedToolsRemove.isEmpty())
                || (allowedToolsDefer != null && !allowedToolsDefer.isEmpty());
    }
}
