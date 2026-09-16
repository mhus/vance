package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One declared invocation argument of a skill — read-side projection
 * of the SKILL.md {@code arguments:} frontmatter (skills.md §2b).
 * Tells a user what trailing text a skill binds into its template
 * before they type {@code /skill <name> <rest…>}.
 *
 * <p>Field shape is identical to {@link ScriptParamDto}: the authoring
 * schema is one dialect (skills.md §2b), this is its read-side twin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillArgumentDto {

    /** Template variable name — bound as {@code args.<name>} in the skill body. */
    private String name;

    /** {@code string} (default), {@code number}, {@code integer}, {@code boolean}, {@code object} or {@code array}. */
    private String type;

    /** Optional human-readable description. */
    private @Nullable String description;

    /** Whether activation fails when the argument is missing. */
    private boolean required;
}
