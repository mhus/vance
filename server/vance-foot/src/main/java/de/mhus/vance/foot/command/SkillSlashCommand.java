package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /skill <name> [--once] [args...]} — explicit skill activation
 * for the active think-process. Sticky by default; passes any trailing
 * args as a follow-up chat message that runs with the freshly
 * activated skill set.
 *
 * <p>Subcommand aliases (kept for tab-friendly entry under the
 * {@code /skill} namespace; the canonical commands are
 * {@code /skill-list} and {@code /skill-clear}):
 * <ul>
 *   <li>{@code /skill list} — print active and available skills</li>
 *   <li>{@code /skill clear} — remove all non-recipe skills</li>
 *   <li>{@code /skill clear &lt;name&gt;} — remove one named skill</li>
 * </ul>
 *
 * <p>See {@code specification/skills.md} §6.
 */
@Component
public class SkillSlashCommand implements SlashCommand {

    private final SkillCommandHelper helper;
    private final ChatTerminal terminal;

    public SkillSlashCommand(SkillCommandHelper helper, ChatTerminal terminal) {
        this.helper = helper;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Activate, clear or list skills on the active process. "
                + "Args: list | clear [name] | <name> [--once] [message...]";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.isEmpty()) {
            terminal.error("Usage: /skill list | clear [name] | <name> [--once] [message...]");
            return;
        }
        String processName = helper.requireActiveProcess();
        if (processName == null) return;

        String head = args.get(0);
        switch (head.toLowerCase()) {
            case "list" -> helper.list(processName);
            case "clear" -> helper.clear(processName, args.size() > 1 ? args.get(1) : null);
            default -> {
                String skillName = args.get(0);
                List<String> rest = args.subList(1, args.size());
                SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(rest);
                helper.activate(processName, skillName, parsed.oneShot(), parsed.trailingTokens());
            }
        }
    }
}
