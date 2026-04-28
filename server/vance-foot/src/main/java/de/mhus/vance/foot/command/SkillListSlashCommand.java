package de.mhus.vance.foot.command;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /skill-list} — print the skills currently active on the
 * focused process plus the cascade-deduped union of skills available
 * in this scope (USER → PROJECT → TENANT → BUNDLED).
 *
 * <p>Top-level alias of {@code /skill list} — kept separate to match
 * the {@code verb-noun} naming convention used by {@code /process-list},
 * {@code /session-list}, etc. See {@code specification/skills.md} §6.
 */
@Component
public class SkillListSlashCommand implements SlashCommand {

    private final SkillCommandHelper helper;

    public SkillListSlashCommand(SkillCommandHelper helper) {
        this.helper = helper;
    }

    @Override
    public String name() {
        return "skill-list";
    }

    @Override
    public String description() {
        return "List active and available skills for the active process.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        String processName = helper.requireActiveProcess();
        if (processName == null) return;
        helper.list(processName);
    }
}
