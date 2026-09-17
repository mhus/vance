package de.mhus.vance.brain.tools.skill;

import de.mhus.vance.brain.skill.SkillSteerProcessor;
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
 * Fires a skill's kickoff turn in the calling think-process — the agent-side
 * twin of what a fresh {@code /skill <name>} does on the user surface.
 * Activation and kickoff are two tools on the agent side:
 * {@code skill_activate} only switches instructions on (the agent calls it
 * mid-turn, a scheduled turn would duplicate the work in flight), and this
 * tool schedules the actual {@code action:} prompt as a new turn when the
 * model wants the work to start.
 *
 * <p>Same gates as every skill path (recipe {@code allowedSkills} locks,
 * disabled refusal, argument validation), never re-runs {@code activate:}
 * — command sequences belong to activation. A sticky skill must already be
 * active; a {@code lifecycle: shot} skill fires directly, which is the
 * prompt-macro execution path. Failure modes come back as honest facts, not
 * errors, mirroring {@code skill_clear}: firing a non-active skill or a
 * spawn skill answers with the "what to do instead" sentence.
 *
 * <p>Process-scoped like its siblings: outside a think-process there is no
 * conversation to schedule a turn in.
 */
@Component
@Slf4j
public class SkillFireTool implements Tool {

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
                                    "Skill name, e.g. 'code-review'. Use how_do_i " + "to discover what exists."));
                    put(
                            "args",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Optional trailing text for this fire — bound into the "
                                            + "skill's prompt template when it declares arguments, "
                                            + "otherwise injected as a user message alongside the "
                                            + "turn. A sticky skill without its own args re-renders "
                                            + "with the args it was activated with."));
                }
            },
            "required",
            List.of("name"));

    private final ThinkProcessService thinkProcessService;
    private final SkillSteerProcessor skillSteerProcessor;

    public SkillFireTool(ThinkProcessService thinkProcessService, SkillSteerProcessor skillSteerProcessor) {
        this.thinkProcessService = thinkProcessService;
        this.skillSteerProcessor = skillSteerProcessor;
    }

    @Override
    public String name() {
        return "skill_fire";
    }

    @Override
    public String description() {
        return "Fire a skill's kickoff turn — schedule its action prompt as a new turn "
                + "in this conversation. skill_activate only switches a skill's "
                + "instructions on; this tool starts the work. A sticky skill must "
                + "already be active (skill_activate first); a shot skill fires "
                + "directly as a one-off macro. Never fires for run.target: spawn "
                + "skills — skill_activate on those starts a fresh worker. Recipe "
                + "locks and disabled skills are enforced exactly as on every skill "
                + "path.";
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

        if (ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("skill_fire needs a running think-process — there is no conversation "
                    + "here to schedule a turn in.");
        }
        ThinkProcessDocument process = thinkProcessService
                .findById(ctx.processId())
                .orElseThrow(() -> new ToolException("unknown process '" + ctx.processId() + "'"));

        SkillSteerProcessor.FireResult result = skillSteerProcessor.fire(process, name, args, ctx.userId());

        log.info("SkillFireTool skill='{}' outcome={} process='{}'", name, result.outcome(), process.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skill", result.skill().name());
        out.put("fired", result.outcome() == SkillSteerProcessor.FireResult.Outcome.FIRED);
        out.put("outcome", result.outcome().name().toLowerCase());
        out.put("note", note(result));
        return out;
    }

    /**
     * The outcome-to-sentence mapping — every non-fired outcome names the
     * "what to do instead" path, the same honesty rule {@code skill_clear}
     * applies: the model asked for a turn and must learn why it did not get
     * one, not just a boolean.
     */
    private String note(SkillSteerProcessor.FireResult result) {
        return switch (result.outcome()) {
            case FIRED -> "Turn scheduled — the skill's prompt runs after the current turn.";
            case NOT_ACTIVE ->
                "Skill '" + result.skill().name()
                        + "' is not active in this conversation — activate it with "
                        + "skill_activate first.";
            case NO_TURN_PROMPT ->
                "Skill '" + result.skill().name()
                        + "' carries no action prompt (and no body as a shot macro) — "
                        + "there is nothing to fire.";
            case SPAWN_SKILL ->
                "Spawn skill — its work happens in a fresh worker; use " + "skill_activate to start it.";
        };
    }
}
