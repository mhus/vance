package de.mhus.vance.brain.tools.skill;

import de.mhus.vance.brain.skill.ActivationRoute;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillLifecycle;
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
                                    "Skill name, e.g. 'design-blueprint'. Use how_do_i " + "to discover what exists."));
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

        // Same funnel as the /skill command — whitelist lock, argument
        // binding, disabled refusal — but route AGENT: activation only
        // switches instructions on, it never fires the skill's action:
        // turn (this call happens mid-turn, a scheduled kick-off would
        // duplicate the work in flight). The explicit kick-off is
        // skill_fire.
        SkillSteerProcessor.ActivationResult result =
                skillSteerProcessor.activate(process, name, once, ActivationRoute.AGENT, args, ctx.userId());

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
        out.put("note", note(result, once));
        return out;
    }

    /**
     * Honest per-outcome sentence, because the outcomes are structurally
     * different: a sticky activation changes this process from the next
     * turn on; a {@code shot} skill never registers (the agent route runs
     * only its activation commands — the turn prompt is skill_fire's job);
     * a {@code run: spawn} skill leaves the calling process untouched — a
     * fresh worker does the work. The plain sticky sentence would be a lie
     * for the other two lifecycles: it would tell the model a skill is
     * active that {@code activeSkills} does not contain. Branch order
     * mirrors the funnel's (spawn before shot) so the two never disagree.
     */
    private String note(SkillSteerProcessor.ActivationResult result, boolean once) {
        ResolvedSkill skill = result.skill();
        if (!result.newlyActivated()) {
            return "Skill was already active (arguments updated if it declares any).";
        }
        if (skill.run().spawns()) {
            return "Spawn skill — a fresh worker process has been started with it; "
                    + "this conversation keeps its current skills and prompt.";
        }
        if (skill.lifecycle() == SkillLifecycle.SHOT) {
            return hasTurnPrompt(skill)
                    ? "Shot skill — its activation commands ran, nothing stays active. Its turn prompt "
                            + "was NOT fired (agent route): call skill_fire to run it."
                    : "Shot skill executed — its activation commands ran; nothing stays active.";
        }
        StringBuilder out = new StringBuilder("Skill is active — its instructions take effect from your next turn.");
        if (once) {
            out.append(" One-shot: it clears itself after that turn.");
        }
        if (hasAction(skill)) {
            out.append(" It carries an action prompt that was NOT fired — call skill_fire "
                    + "when you want it to kick off its work.");
        }
        return out.toString();
    }

    private static boolean hasTurnPrompt(ResolvedSkill skill) {
        return hasAction(skill)
                || (skill.promptExtension() != null && !skill.promptExtension().isBlank());
    }

    private static boolean hasAction(ResolvedSkill skill) {
        return skill.action() != null && !skill.action().isBlank();
    }
}
