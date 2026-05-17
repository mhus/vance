package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-side view of one skill-script entry — the metadata an editor
 * UI or admin REST consumer sees. The script body itself is not
 * carried here; only the descriptor.
 *
 * <p>See {@code specification/skills.md} §13.1 for the on-disk
 * representation (frontmatter list + sibling files).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillScriptDto {

    /** Stable identifier within the skill. Combined with the skill
     *  name to form the virtual tool name
     *  {@code skill_<skill>__<script>}. */
    private String name;

    /** Where the script runs. See {@link ScriptTarget}. */
    private ScriptTarget target;

    /** Optional short description — shown next to the script in the
     *  LLM's tool-picker. Kept brief; the skill body is where
     *  trigger-instructions live. */
    private @Nullable String description;
}
