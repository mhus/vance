package de.mhus.vance.brain.tools.skill;

import de.mhus.vance.brain.skill.ActivationRoute;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillLifecycle;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillSteerProcessor;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
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
                                    "Skill name, e.g. 'design-blueprint'. Skills advertised "
                                            + "under 'Available skills' in your context are yours to "
                                            + "activate; how_do_i discovers the rest."));
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
                                    "Force a one-turn activation (same as '/skill <name> --once'). "
                                            + "Default: the skill's own lifecycle."));
                    put(
                            "sticky",
                            Map.of(
                                    "type",
                                    "boolean",
                                    "description",
                                    "Keep a task skill active beyond this turn (same as "
                                            + "'/skill <name> --sticky'). Rarely needed — task skills "
                                            + "clear themselves once the task is done."));
                }
            },
            "required",
            List.of("name"));

    private final ThinkProcessService thinkProcessService;
    private final SkillSteerProcessor skillSteerProcessor;
    /** Renders the activated skill's instructions into the tool result. */
    private final SkillPromptComposer skillPromptComposer;

    public SkillActivateTool(
            ThinkProcessService thinkProcessService,
            SkillSteerProcessor skillSteerProcessor,
            SkillPromptComposer skillPromptComposer) {
        this.thinkProcessService = thinkProcessService;
        this.skillSteerProcessor = skillSteerProcessor;
        this.skillPromptComposer = skillPromptComposer;
    }

    @Override
    public String name() {
        return de.mhus.vance.brain.skill.SkillTurnSupport.ACTIVATE_TOOL;
    }

    @Override
    public String description() {
        return "Activate a skill in the running conversation — the agent-side "
                + "equivalent of the user's /skill command. The result carries the "
                + "skill's instructions: follow them for the task at hand, starting "
                + "right away. Most skills are task skills and clear themselves after "
                + "this turn; standing skills stay until skill_clear. Use it when a "
                + "skill matches the task (e.g. a 'design' skill before creating web "
                + "designs in a designer app) instead of working without it. Recipe "
                + "allowedSkills locks, argument declarations and disabled skills are "
                + "enforced exactly as on the /skill surface. Skills offered for the "
                + "current work are listed under 'Available skills' in your context; "
                + "how_do_i discovers the rest.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, "skill", "process");
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
        boolean sticky = params.get("sticky") instanceof Boolean st && st;

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
                skillSteerProcessor.activate(process, name, once, sticky, ActivationRoute.AGENT, args, ctx.userId());

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
        boolean oneTurn = result.activeAfter().stream()
                .filter(r -> result.skill().name().equals(r.getName()))
                .anyMatch(ActiveSkillRefEmbedded::isOneShot);
        // The instructions come back right here (progressive disclosure):
        // the system prompt of this turn was built before the call, so
        // without them a skill activated mid-turn would only work from the
        // next turn — and a one-turn skill, cleared after this turn, never.
        String instructions = result.newlyActivated() && registersInline(result.skill())
                ? instructions(process, result.skill(), args)
                : null;
        if (instructions != null) {
            out.put("instructions", instructions);
        }
        out.put("note", note(result, oneTurn, instructions != null));
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
    private String note(SkillSteerProcessor.ActivationResult result, boolean oneTurn, boolean withInstructions) {
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
        StringBuilder out = new StringBuilder(
                withInstructions
                        ? "Skill is active — follow its instructions below for this task, starting now."
                        : "Skill is active.");
        out.append(
                oneTurn
                        ? " It applies to this turn only and clears itself afterwards."
                        : " It stays active: from your next turn on its instructions are part of your context;"
                                + " call skill_clear when the task no longer needs it.");
        if (hasAction(skill)) {
            out.append(" It carries an action prompt that was NOT fired — call skill_fire "
                    + "when you want it to kick off its work.");
        }
        return out.toString();
    }

    /** Whether the activation registered the skill on this process (not a macro, not a spawn). */
    private static boolean registersInline(ResolvedSkill skill) {
        return skill.lifecycle() != SkillLifecycle.SHOT && !skill.run().spawns();
    }

    /**
     * The skill's rendered instructions — body, INLINE reference docs and
     * ON_DEMAND pointers, exactly as the system prompt would carry them,
     * against the process's prompt context plus the invocation arguments.
     */
    private @org.jspecify.annotations.Nullable String instructions(
            ThinkProcessDocument process, ResolvedSkill skill, @org.jspecify.annotations.Nullable String args) {
        Map<String, String> argsByName = new java.util.HashMap<>();
        if (args != null) argsByName.put(skill.name(), args);
        String composed = skillPromptComposer.compose(
                List.of(skill),
                de.mhus.vance.brain.prompt.PromptContextBuilder.forProcess(process, null)
                        .build(),
                argsByName);
        return composed == null || composed.isBlank() ? null : composed.strip();
    }

    private static boolean hasTurnPrompt(ResolvedSkill skill) {
        return hasAction(skill)
                || (skill.promptExtension() != null && !skill.promptExtension().isBlank());
    }

    private static boolean hasAction(ResolvedSkill skill) {
        return skill.action() != null && !skill.action().isBlank();
    }
}
