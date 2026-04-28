package de.mhus.vance.foot.command;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /skill-clear [name]} — deactivate skills on the focused
 * process. Without an argument, clears all non-recipe skills (recipe-
 * bound skills on a locked recipe stay). With a name, clears that one
 * skill only.
 *
 * <p>Top-level alias of {@code /skill clear [name]} — kept separate to
 * match the {@code verb-noun} naming convention. See
 * {@code specification/skills.md} §6.
 */
@Component
public class SkillClearSlashCommand implements SlashCommand {

    private final SkillCommandHelper helper;

    public SkillClearSlashCommand(SkillCommandHelper helper) {
        this.helper = helper;
    }

    @Override
    public String name() {
        return "skill-clear";
    }

    @Override
    public String description() {
        return "Deactivate skills on the active process. Args: [name].";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        String processName = helper.requireActiveProcess();
        if (processName == null) return;
        String skillName = args.isEmpty() ? null : args.get(0);
        helper.clear(processName, skillName);
    }
}
