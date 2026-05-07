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
 * <p>See {@code planning/tool-schema-deferral.md} §14.
 */
public record RecipeModeBlock(
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        List<String> allowedToolsDefer) {

    public static final RecipeModeBlock EMPTY =
            new RecipeModeBlock(List.of(), List.of(), List.of());

    /** {@code true} when none of the three lists carry an entry. */
    public boolean isEmpty() {
        return (allowedToolsAdd == null || allowedToolsAdd.isEmpty())
                && (allowedToolsRemove == null || allowedToolsRemove.isEmpty())
                && (allowedToolsDefer == null || allowedToolsDefer.isEmpty());
    }
}
