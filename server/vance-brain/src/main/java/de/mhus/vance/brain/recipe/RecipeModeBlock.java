package de.mhus.vance.brain.recipe;

import java.util.List;

/**
 * Per-mode tool-filter overlay inside a {@link ProfileBlock}. Applied
 * by {@link RecipeResolver#toolFilterFor} via the cascade
 * {@code profiles[profile].modes[mode] → modes["default"] → profile-base
 * → profiles["default"] → recipe-base}.
 *
 * <p>Override semantics, not accumulation — when the resolver finds a
 * mode-block, only its lists are applied; outer layers are not merged.
 * Wer einen Mode bewusst „leeren" will, schreibt {@code modes.<X>: {}}
 * (Profile-Base wirkt durch die Cascade-Stufe 3).
 *
 * <p>List entries may be literal tool names or {@code @<label>}
 * selectors (expanded by the resolver via
 * {@code ServerToolService#findByLabel}).
 *
 * <p>{@code allowedToolsKeep} / {@code allowedToolsDropFirst} do not
 * change visibility — they only order the tools for the tool-surface
 * budget when the endpoint's {@code tools}-array cap forces a cut
 * ({@code planning/tool-surface-budget.md}). Without a cap they have no
 * effect at all.
 *
 * <p>See {@code planning/tool-schema-deferral.md} §14.
 */
public record RecipeModeBlock(
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        List<String> allowedToolsDefer,
        List<String> allowedToolsKeep,
        List<String> allowedToolsDropFirst) {

    public static final RecipeModeBlock EMPTY =
            new RecipeModeBlock(List.of(), List.of(), List.of(), List.of(), List.of());

    /**
     * Overlay without budget-priority hints — the three lists that
     * existed before {@code allowedToolsKeep} / {@code allowedToolsDropFirst}
     * were introduced.
     */
    public RecipeModeBlock(
            List<String> allowedToolsAdd,
            List<String> allowedToolsRemove,
            List<String> allowedToolsDefer) {
        this(allowedToolsAdd, allowedToolsRemove, allowedToolsDefer, List.of(), List.of());
    }

    /**
     * {@code true} when no <em>visibility</em> list carries an entry.
     *
     * <p>Same reasoning as {@link ProfileBlock#hasToolFilter()}: the
     * visibility cascade is first-hit-wins, so a block that only ranks
     * tools must not win the lookup and shadow an outer add/defer list.
     * Its priority hints are still picked up — those are unioned across
     * the cascade in {@link RecipeResolver#toolFilterFor}.
     */
    public boolean isEmpty() {
        return (allowedToolsAdd == null || allowedToolsAdd.isEmpty())
                && (allowedToolsRemove == null || allowedToolsRemove.isEmpty())
                && (allowedToolsDefer == null || allowedToolsDefer.isEmpty());
    }
}
