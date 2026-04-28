package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A JavaScript snippet bundled with a skill.
 *
 * <p>{@code name} is the script's identifier inside the skill — used
 * as part of the future tool name (Phase 2). {@code target} decides
 * where it executes; {@code content} is the source.
 *
 * <p>{@code description} is an optional one-liner that becomes the
 * tool description the LLM sees once Phase 2 mounts scripts as
 * tools — without it, the model has only the script name to go on
 * when deciding whether to call.
 *
 * <p>v1 stores and edits these records. Runtime mounting (Phase 2)
 * and host-bindings / sandbox semantics (Phase 3) are deferred — see
 * {@code specification/skills.md} §13.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillScriptDto {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
            message = "must be lower-case alphanumerics with optional '-' or '_'")
    private String name;

    private @Nullable String description;

    @NotNull
    @Builder.Default
    private SkillScriptTarget target = SkillScriptTarget.BRAIN;

    @NotBlank
    private String content;
}
