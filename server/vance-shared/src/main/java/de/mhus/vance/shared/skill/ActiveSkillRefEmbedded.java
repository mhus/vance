package de.mhus.vance.shared.skill;

import de.mhus.vance.api.skills.SkillScope;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mongo-embedded reference to a skill that is currently active on a
 * {@code ThinkProcessDocument}. Persisted in the process document so
 * resume after a brain restart picks the same active skill set up
 * again.
 *
 * <p>{@link #fromRecipe} marks skills that were activated by the
 * recipe's {@code skills:} list. When the recipe is locked, the user
 * cannot deactivate those via {@code /skill clear}; non-recipe
 * activations remain freely toggleable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveSkillRefEmbedded {

    private String name = "";

    private SkillScope resolvedFromScope = SkillScope.BUNDLED;

    /** {@code true} for {@code /skill <name> --once} activations. */
    private boolean oneShot;

    /** {@code true} when contributed by the spawning recipe's skills list. */
    private boolean fromRecipe;

    private @Nullable Instant activatedAt;
}
