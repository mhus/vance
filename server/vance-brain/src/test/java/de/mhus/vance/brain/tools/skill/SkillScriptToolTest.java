package de.mhus.vance.brain.tools.skill;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.skills.ScriptTarget;
import de.mhus.vance.brain.skill.ResolvedSkill;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic coverage of {@link SkillScriptTool#paramsSchema()} — the
 * bridge from a script's declared {@code params} to the JSON-Schema
 * the LLM tool-loop sees. No Brain bootstrap, no executor needed
 * (paramsSchema never touches the {@link de.mhus.vance.brain.script.ScriptExecutor}).
 */
class SkillScriptToolTest {

    private static SkillScriptTool toolFor(List<ResolvedSkill.Script.ScriptParam> params) {
        ResolvedSkill.Script script = new ResolvedSkill.Script(
                "greet", ScriptTarget.BRAIN, "desc", params, "return {};");
        return new SkillScriptTool("hello-script", script, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void paramsSchema_declaredParams_rendersTypedPropertiesAndRequired() {
        SkillScriptTool tool = toolFor(List.of(
                new ResolvedSkill.Script.ScriptParam("name", "string", "Who to greet.", true),
                new ResolvedSkill.Script.ScriptParam("loud", "boolean", null, false)));

        Map<String, Object> schema = tool.paramsSchema();

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("name", "loud");
        assertThat((Map<String, Object>) properties.get("name"))
                .containsEntry("type", "string")
                .containsEntry("description", "Who to greet.");
        assertThat((Map<String, Object>) properties.get("loud"))
                .containsEntry("type", "boolean")
                .doesNotContainKey("description");
        assertThat((List<String>) schema.get("required"))
                .containsExactly("name");
        // Undeclared optional args stay allowed.
        assertThat(schema).containsEntry("additionalProperties", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void paramsSchema_noRequiredParams_omitsRequiredKey() {
        SkillScriptTool tool = toolFor(List.of(
                new ResolvedSkill.Script.ScriptParam("name", "string", null, false)));

        Map<String, Object> schema = tool.paramsSchema();

        assertThat(schema).doesNotContainKey("required");
        assertThat((Map<String, Object>) schema.get("properties")).containsOnlyKeys("name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void paramsSchema_noParams_keepsFreeFormBag() {
        SkillScriptTool tool = toolFor(List.of());

        Map<String, Object> schema = tool.paramsSchema();

        assertThat(schema).containsEntry("type", "object");
        assertThat((Map<String, Object>) schema.get("properties")).isEmpty();
        assertThat(schema).containsEntry("additionalProperties", true);
        assertThat(schema).doesNotContainKey("required");
    }
}
