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
 * Clears skills in the calling think-process — the agent-side twin of the
 * user's {@code /skill clear [name]} command, on the exact same funnel
 * ({@link SkillSteerProcessor}): recipe-bound skills stay (they belong to
 * the recipe for the process's lifetime, skills.md §7a) and a cleared
 * skill's {@code deactivate:} cleanup commands fire best-effort.
 *
 * <p><b>Why an agent needs this at all.</b> A sticky skill the agent
 * activated keeps its body in every following turn's system prompt and
 * its tools in the whitelist. When the phase that justified the skill is
 * over, the agent should end it instead of dragging the context along —
 * the same hygiene the user applies with {@code /skill clear}.
 *
 * <p><b>What it does not decide.</b> The tool does not guess whether a
 * skill is still wanted: a name that is not active, or one the recipe
 * pinned, comes back as an honest note instead of a silent no-op or an
 * error. Process-scoped like {@code skill_activate}: outside a
 * think-process there is nothing to clear.
 */
@Component
@Slf4j
public class SkillClearTool implements Tool {

    private static final Map<String, Object> SCHEMA =
            Map.of("type", "object", "properties", new LinkedHashMap<String, Object>() {
                {
                    put(
                            "name",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Skill name to clear, e.g. 'design-blueprint'. Omit to "
                                            + "clear ALL non-recipe skills — use that sparingly; "
                                            + "a named clear is the precise cleanup."));
                }
            });

    private final ThinkProcessService thinkProcessService;
    private final SkillSteerProcessor skillSteerProcessor;

    public SkillClearTool(ThinkProcessService thinkProcessService, SkillSteerProcessor skillSteerProcessor) {
        this.thinkProcessService = thinkProcessService;
        this.skillSteerProcessor = skillSteerProcessor;
    }

    @Override
    public String name() {
        return "skill_clear";
    }

    @Override
    public String description() {
        return "Clear active skills in the running conversation — the agent-side "
                + "equivalent of the user's /skill clear command. The skill's prompt "
                + "extension and tool whitelist stop applying from the next turn on, "
                + "and its deactivate cleanup commands run. Use it when a phase that "
                + "justified a skill is over (e.g. the design work is done and the "
                + "'design' skill no longer applies) instead of leaving its "
                + "instructions in every following turn. Recipe-bound skills stay "
                + "active for the process lifetime — that is reported, not an error. "
                + "Prefer clearing one named skill over clearing all.";
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

        if (ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("skill_clear needs a running think-process — there is no conversation "
                    + "here to clear a skill from.");
        }
        ThinkProcessDocument process = thinkProcessService
                .findById(ctx.processId())
                .orElseThrow(() -> new ToolException("unknown process '" + ctx.processId() + "'"));

        // The funnel returns the post-mutation list only, so the tool reads
        // "was active / recipe-bound" from the snapshot it hands in — the
        // same distinction the /skill clear sentence makes.
        List<ActiveSkillRefEmbedded> before =
                process.getActiveSkills() == null ? List.of() : List.copyOf(process.getActiveSkills());

        List<ActiveSkillRefEmbedded> after;
        if (name == null) {
            after = skillSteerProcessor.clearAll(process);
        } else {
            after = skillSteerProcessor.clear(process, name);
        }

        List<String> beforeNames =
                before.stream().map(ActiveSkillRefEmbedded::getName).toList();
        List<String> afterNames =
                after.stream().map(ActiveSkillRefEmbedded::getName).toList();
        List<String> cleared =
                beforeNames.stream().filter(n -> !afterNames.contains(n)).toList();
        List<String> keptRecipeBound = after.stream()
                .filter(ActiveSkillRefEmbedded::isFromRecipe)
                .map(ActiveSkillRefEmbedded::getName)
                .toList();

        log.info(
                "SkillClearTool name='{}' cleared={} keptRecipeBound={} process='{}'",
                name,
                cleared.size(),
                keptRecipeBound.size(),
                process.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skill", name);
        out.put("cleared", cleared);
        out.put("keptRecipeBound", keptRecipeBound);
        out.put("activeSkills", afterNames);
        out.put("note", note(name, cleared, beforeNames, keptRecipeBound));
        return out;
    }

    private String note(String name, List<String> cleared, List<String> beforeNames, List<String> keptRecipeBound) {
        if (name == null) {
            if (cleared.isEmpty()) {
                return keptRecipeBound.isEmpty()
                        ? "No clearable skills were active."
                        : "Only recipe-bound skills are active — they stay for the process lifetime.";
            }
            return "Cleared " + String.join(", ", cleared) + "."
                    + (keptRecipeBound.isEmpty()
                            ? ""
                            : " Recipe-bound (kept): " + String.join(", ", keptRecipeBound) + ".");
        }
        if (cleared.contains(name)) {
            return "Skill '" + name + "' cleared — its instructions stop applying from your next turn "
                    + "and its cleanup commands have run.";
        }
        if (!beforeNames.contains(name)) {
            return "Skill '" + name + "' was not active.";
        }
        // Active before and after, not cleared — the funnel kept it.
        return "Skill '" + name + "' is recipe-bound and stays active for the process lifetime.";
    }
}
