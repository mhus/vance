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
 * <p>{@code manuals} from the spec is intentionally not parsed yet — the
 * doc/manual consolidation is still open ({@code instructions/_todo.md}).
 * When manuals are wired, the field is added here without breaking the
 * recipe schema.
 */
public record ProfileBlock(
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        @Nullable String promptPrefixAppend,
        Map<String, Object> params,
        @Nullable SessionLifecycleConfig sessionLifecycleConfig) {

    /** Reusable empty block for profiles that have no overlay. */
    public static final ProfileBlock EMPTY =
            new ProfileBlock(List.of(), List.of(), null, Map.of(), null);
}
