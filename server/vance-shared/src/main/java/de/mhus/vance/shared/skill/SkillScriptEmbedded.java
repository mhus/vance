package de.mhus.vance.shared.skill;

import de.mhus.vance.api.skills.SkillScriptTarget;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mongo-embedded representation of a JavaScript snippet bundled with a
 * skill. Mirrors {@code SkillScriptDto} from vance-api.
 *
 * <p>{@code description} feeds the future tool description (Phase 2)
 * — keeping it close to the script makes the catalog readable when
 * the LLM picks which one to invoke.
 *
 * <p>v1 only persists; runtime mounting is a later phase (see
 * {@code specification/skills.md} §13).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillScriptEmbedded {

    private String name = "";

    private @Nullable String description;

    private SkillScriptTarget target = SkillScriptTarget.BRAIN;

    private String content = "";
}
