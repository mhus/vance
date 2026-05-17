package de.mhus.vance.brain.tools.skill;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One script entry on an active skill, surfaced as a virtual tool
 * into the engine's tool-loop. Tool-name is composed as
 * {@code skill_<skillName>__<scriptName>} per
 * {@code specification/skills.md} §13.2.
 *
 * <p>Stateless per instance — created on demand by
 * {@link SkillScriptToolSource} every time the engine queries the
 * dispatcher. Holds the JS body verbatim; execution goes through the
 * sandboxed {@link ScriptExecutor} with the engine's bound
 * {@link ContextToolsApi}, so script-initiated tool calls go through
 * the same allow-filter, permission and listener path as LLM-issued
 * ones (§13.4 of skills.md).
 */
public final class SkillScriptTool implements Tool {

    public static final String NAME_SEPARATOR = "__";
    public static final String NAME_PREFIX = "skill_";

    /** Default per-call timeout. Skill-script authors haven't asked
     *  for an override yet — re-evaluate once Phase 3 quota work
     *  (§13.4) lands. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String skillName;
    private final ResolvedSkill.Script script;
    private final ScriptExecutor scriptExecutor;

    public SkillScriptTool(
            String skillName,
            ResolvedSkill.Script script,
            ScriptExecutor scriptExecutor) {
        this.skillName = skillName;
        this.script = script;
        this.scriptExecutor = scriptExecutor;
    }

    /** Compose the virtual tool name for a given (skill, script) pair —
     *  exposed so {@link SkillScriptToolSource} can pre-filter
     *  {@code find(name, ctx)} without instantiating a Tool. */
    public static String composeName(String skillName, String scriptName) {
        return NAME_PREFIX + skillName + NAME_SEPARATOR + scriptName;
    }

    @Override
    public String name() {
        return composeName(skillName, script.name());
    }

    @Override
    public String description() {
        String desc = script.description();
        if (desc != null && !desc.isBlank()) {
            return desc + " (skill-script — from skill '" + skillName + "')";
        }
        return "Skill-script '" + script.name() + "' contributed by skill '"
                + skillName + "'. See the skill's body for invocation guidance.";
    }

    @Override
    public boolean primary() {
        // The LLM only sees this tool when the parent skill is active,
        // and the skill body explains when to invoke it — make it
        // first-class in the listing so the LLM doesn't have to dig
        // through find_tools.
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        // v1: free-form bag — the script does its own shape checks.
        // Phase 3 (§13.4) can grow this to declared schemas.
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", true);
    }

    @Override
    public Set<String> labels() {
        return Set.of("skill-script");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        throw new ToolException(
                "skill-script tool '" + name() + "' requires the bound tools surface "
                        + "— call via the engine's ContextToolsApi");
    }

    @Override
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx, ToolBus busArg) {
        ContextToolsApi tools = (ContextToolsApi) busArg;
        // The 'vance' binding is injected automatically by the executor.
        // We expose the caller's params under a top-level 'args' so the
        // script can read them without a wrapper object.
        Map<String, @Nullable Object> bindings = new LinkedHashMap<>();
        bindings.put("args", params == null ? Map.of() : params);
        try {
            ScriptResult result = scriptExecutor.run(new ScriptRequest(
                    "js",
                    script.body(),
                    "skill:" + skillName + "/" + script.name(),
                    tools,
                    DEFAULT_TIMEOUT,
                    bindings));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("value", result.value());
            out.put("durationMs", result.duration().toMillis());
            return out;
        } catch (ScriptExecutionException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.errorClass().name());
            out.put("message", e.getMessage());
            return out;
        }
    }

    /** Test/debug helper — the skill that contributed this tool. */
    @SuppressWarnings("unused")
    String skillName() {
        return skillName;
    }

    /** Test/debug helper — the resolved script descriptor. */
    @SuppressWarnings("unused")
    ResolvedSkill.Script script() {
        return script;
    }

    /** Test/debug helper — never used in production paths but lets
     *  tests round-trip the list of scripts a source emits. */
    @SuppressWarnings("unused")
    static List<SkillScriptTool> dummyList() {
        return List.of();
    }
}
