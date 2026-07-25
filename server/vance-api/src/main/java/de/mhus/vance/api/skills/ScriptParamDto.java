package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One declared parameter of a skill-script — the typed contract a
 * script exposes to the LLM tool-loop. Rendered into the virtual
 * tool's JSON-Schema by {@code SkillScriptTool.paramsSchema()} so
 * even weak models get an explicit parameter list instead of relying
 * on prose in the skill body.
 *
 * <p>See {@code specification/skills.md} §13.7. Declaring params is
 * optional: a script with no {@code params} keeps the free-form v1
 * behaviour (empty schema, {@code additionalProperties: true}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class ScriptParamDto {

    /** Parameter name — the key the LLM passes in the tool-call and
     *  the script reads from {@code args}. */
    private String name;

    /** JSON-Schema primitive type: {@code string}, {@code number},
     *  {@code integer}, {@code boolean}, {@code object} or
     *  {@code array}. */
    private String type;

    /** Optional human-readable description shown to the LLM. */
    private @Nullable String description;

    /** Whether the LLM must supply this parameter. Required params
     *  land in the schema's {@code required} array. */
    private boolean required;
}
