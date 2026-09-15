package de.mhus.vance.brain.tools.skill;

import de.mhus.vance.brain.skill.SkillSteerProcessor;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Activates a skill in the calling think-process — the agent-side twin of
 * the user's {@code /skill <name>} command, on the exact same funnel
 * ({@link SkillSteerProcessor#activate}): recipe {@code allowedSkills}
 * locks, argument binding, disabled refusal and trigger-less skills all
 * behave identically.
 *
 * <p><b>Why this is safe where it matters.</b> A skill's body lands in the
 * process's <em>own</em> system prompt — self-configuration, not access:
 * the caller cannot reach anything it could not reach before (tool
 * whitelists only ever add what the recipe allows, and skills come from
 * the trusted cascade — bundled, tenant, project). What the tool does not
 * do is guess: an unknown or disabled name fails loudly instead of
 * degrading to "no skill active".
 *
 * <p>Process-scoped: requires a {@code processId} on the invocation
 * context — outside a think-process (CLI one-shots, MCP calls) there is
 * nothing to activate into, and the tool says so.
 */
@Component
@Slf4j
public class SkillActivateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put(
                            "name",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Skill name, e.g. 'design-blueprint'. See /skill list "
                                            + "or how_do_i for what exists."));
                    put(
                            "args",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Optional trailing text the skill binds as its "
                                            + "arguments (same semantics as '/skill <name> <rest>')."));
                    put(
                            "once",
                            Map.of(
                                    "type",
                                    "boolean",
                                    "description",
                                    "One-turn activation (same as '/skill <name> --once'). "
                                            + "Default false (sticky)."));
                }
            },
            "required",
            List.of("name"));

    private final ThinkProcessService thinkProcessService;
    private final SkillSteerProcessor skillSteerProcessor;

    public SkillActivateTool(ThinkProcessService thinkProcessService, SkillSteerProcessor skillSteerProcessor) {
        this.thinkProcessService = thinkProcessService;
        this.skillSteerProcessor = skillSteerProcessor;
    }

    @Override
    public String name() {
        return "skill_activate";
    }

    @Override
    public String description() {
        return "Activate a skill in the running conversation — the agent-side "
                + "equivalent of the user's /skill command. The skill's prompt "
                + "extension, tool whitelist and reference docs take effect from "
                + "the next turn on. Use it when a skill matches the task (e.g. a "
                + "'design' skill before creating web designs in a designer app) "
                + "instead of working without it. Recipe allowedSkills locks, "
                + "argument declarations and disabled skills are enforced exactly "
                + "as on the /skill surface.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "skill", "process");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String name = params.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (name == null) throw new ToolException("name is required");
        String args = params.get("args") instanceof String a && !a.isBlank() ? a : null;
        boolean once = params.get("once") instanceof Boolean b && b;

        if (ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("skill_activate needs a running think-process — there is no conversation "
                    + "here to activate a skill into.");
        }
        ThinkProcessDocument process = thinkProcessService
                .findById(ctx.processId())
                .orElseThrow(() -> new ToolException("unknown process '" + ctx.processId() + "'"));

        // Same funnel as the /skill command: whitelist lock, argument
        // binding, disabled refusal, action-turn firing.
        SkillSteerProcessor.ActivationResult result =
                skillSteerProcessor.activate(process, name, once, args, ctx.userId());

        log.info(
                "SkillActivateTool skill='{}' once={} newlyActivated={} process='{}'",
                name,
                once,
                result.newlyActivated(),
                process.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skill", result.skill().name());
        out.put("lifecycle", result.skill().lifecycle().name().toLowerCase());
        out.put("newlyActivated", result.newlyActivated());
        out.put(
                "activeSkills",
                result.activeAfter().stream()
                        .map(ActiveSkillRefEmbedded::getName)
                        .toList());
        out.put(
                "note",
                result.newlyActivated()
                        ? "Skill is active — its instructions take effect from your next turn."
                        : "Skill was already active (arguments updated if it declares any).");
        return out;
    }
}
