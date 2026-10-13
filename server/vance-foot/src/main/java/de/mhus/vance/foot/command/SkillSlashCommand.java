package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /skill <name> [--once|--sticky] [args...]} — explicit skill activation
 * for the active think-process. Sticky by default. Trailing args go to
 * the brain as the invocation's raw arguments: a skill that declares
 * {@code arguments:} renders them into its prompt, any other skill gets
 * them as a plain user message.
 *
 * <p>Subcommand aliases (kept for tab-friendly entry under the
 * {@code /skill} namespace; the canonical commands are
 * {@code /skill-list} and {@code /skill-clear}):
 * <ul>
 *   <li>{@code /skill list} — print active and available skills</li>
 *   <li>{@code /skill clear} — remove all non-recipe skills</li>
 *   <li>{@code /skill clear &lt;name&gt;} — remove one named skill</li>
 *   <li>{@code /skill mode &lt;name&gt; &lt;mode&gt; [scope]} — set the activation mode
 *       (manual | trigger | agent) in the user override tier, or in the
 *       project/tenant tier when named</li>
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
        return "Activate, clear, list or re-mode skills on the active process. "
                + "Args: list | clear [name] | mode <name> <manual|trigger|agent> [scope]"
                + " | <name> [--once|--sticky] [args...]";
    }

    @Override
    public List<ArgSpec> argSpec() {
        // Mixed ENUM+SKILL: the first token is "list", "clear", or a skill
        // name. We surface the static subcommands here; users can still
        // type a skill name freely (the brain validates).
        return List.of(ArgSpec.enumOf("subcommand", List.of("list", "clear", "mode")));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.isEmpty()) {
            terminal.error(
                    "Usage: /skill list | clear [name] | mode <name> <mode> [scope] | <name> [--once|--sticky] [args...]");
            return;
        }
        String head = args.get(0);
        // The mode is scope configuration, not process state — it needs no
        // active process, only the bound session's project.
        if (head.equalsIgnoreCase("mode")) {
            if (args.size() < 3) {
                terminal.error("Usage: /skill mode <name> <manual|trigger|agent> [user|project|tenant]");
                return;
            }
            helper.setMode(args.get(1), args.get(2), args.size() > 3 ? args.get(3) : null);
            return;
        }
        String processName = helper.requireActiveProcess();
        if (processName == null) return;

        switch (head.toLowerCase()) {
            case "list" -> helper.list(processName);
            case "clear" -> helper.clear(processName, args.size() > 1 ? args.get(1) : null);
            default -> {
                String skillName = args.get(0);
                List<String> rest = args.subList(1, args.size());
                SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(rest);
                helper.activate(processName, skillName, parsed.oneShot(), parsed.sticky(), parsed.trailingTokens());
            }
        }
    }
}
