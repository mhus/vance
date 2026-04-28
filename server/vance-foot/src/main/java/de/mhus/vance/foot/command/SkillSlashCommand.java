package de.mhus.vance.foot.command;

import de.mhus.vance.api.skills.ActiveSkillRefDto;
import de.mhus.vance.api.skills.ProcessSkillCommand;
import de.mhus.vance.api.skills.ProcessSkillRequest;
import de.mhus.vance.api.skills.ProcessSkillResponse;
import de.mhus.vance.api.skills.SkillDto;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code /skill <name> [--once] [args...]} — explicit skill activation
 * for the active think-process. Sticky by default; passes any trailing
 * args as a follow-up chat message that will run with the freshly
 * activated skill set.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /skill list} — print active and available skills</li>
 *   <li>{@code /skill clear} — remove all non-recipe skills</li>
 *   <li>{@code /skill clear &lt;name&gt;} — remove one named skill</li>
 *   <li>{@code /skill &lt;name&gt; [--once] [message...]} — activate
 *       and optionally send the same line as a chat message</li>
 * </ul>
 *
 * <p>See {@code specification/skills.md} §6.
 */
@Component
public class SkillSlashCommand implements SlashCommand {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final SessionService sessions;

    public SkillSlashCommand(
            ConnectionService connection,
            ChatTerminal terminal,
            SessionService sessions) {
        this.connection = connection;
        this.terminal = terminal;
        this.sessions = sessions;
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
        String processName = sessions.activeProcess();
        if (processName == null || processName.isBlank()) {
            terminal.error("No active process — use /process-activate first");
            return;
        }

        String head = args.get(0);
        switch (head.toLowerCase()) {
            case "list" -> doList(processName);
            case "clear" -> doClear(processName, args);
            default -> doActivate(processName, args);
        }
    }

    private void doList(String processName) throws Exception {
        ProcessSkillResponse response = sendRequest(ProcessSkillRequest.builder()
                .processName(processName)
                .command(ProcessSkillCommand.LIST)
                .build());

        Set<String> activeNames = new HashSet<>();
        for (ActiveSkillRefDto ref : response.getActiveSkills()) {
            activeNames.add(ref.getName());
        }
        if (response.getActiveSkills().isEmpty()) {
            terminal.info("(no active skills)");
        } else {
            terminal.info("active skills:");
            for (ActiveSkillRefDto ref : response.getActiveSkills()) {
                terminal.info("  ✓ " + ref.getName()
                        + "  [" + ref.getResolvedFromScope() + "]"
                        + (ref.isOneShot() ? "  (once)" : ""));
            }
        }
        if (response.getAvailableSkills().isEmpty()) {
            terminal.info("(no skills available in this scope)");
            return;
        }
        terminal.info("available:");
        for (SkillDto dto : response.getAvailableSkills()) {
            String marker = activeNames.contains(dto.getName()) ? "  ✓ " : "    ";
            terminal.info(marker + dto.getName()
                    + "  [" + dto.getScope() + "]"
                    + "  — " + dto.getDescription());
        }
    }

    private void doClear(String processName, List<String> args) throws Exception {
        if (args.size() == 1) {
            ProcessSkillResponse response = sendRequest(ProcessSkillRequest.builder()
                    .processName(processName)
                    .command(ProcessSkillCommand.CLEAR_ALL)
                    .build());
            terminal.info("cleared. active skills now: " + response.getActiveSkills().size());
            return;
        }
        String skillName = args.get(1);
        ProcessSkillResponse response = sendRequest(ProcessSkillRequest.builder()
                .processName(processName)
                .command(ProcessSkillCommand.CLEAR)
                .skillName(skillName)
                .build());
        terminal.info("cleared '" + skillName + "'. active skills now: "
                + response.getActiveSkills().size());
    }

    private void doActivate(String processName, List<String> args) throws Exception {
        String skillName = args.get(0);
        boolean oneShot = false;
        List<String> remaining = new ArrayList<>(args.subList(1, args.size()));
        // --once may appear anywhere in the trailing args.
        for (int i = 0; i < remaining.size(); i++) {
            if ("--once".equals(remaining.get(i))) {
                oneShot = true;
                remaining.remove(i);
                break;
            }
        }

        ProcessSkillResponse response = sendRequest(ProcessSkillRequest.builder()
                .processName(processName)
                .command(ProcessSkillCommand.ACTIVATE)
                .skillName(skillName)
                .oneShot(oneShot)
                .build());
        terminal.info("→ skill '" + skillName + "' activated"
                + (oneShot ? " (once)" : "")
                + ". active skills now: " + response.getActiveSkills().size());

        if (!remaining.isEmpty()) {
            String content = String.join(" ", remaining);
            ProcessSteerResponse steer = connection.request(
                    MessageType.PROCESS_STEER,
                    ProcessSteerRequest.builder()
                            .processName(processName)
                            .content(content)
                            .build(),
                    ProcessSteerResponse.class,
                    Duration.ofSeconds(60));
            terminal.info("→ steered " + steer.getProcessName()
                    + " (status=" + steer.getStatus() + ")");
        }
    }

    private ProcessSkillResponse sendRequest(ProcessSkillRequest request) throws Exception {
        return connection.request(
                MessageType.PROCESS_SKILL,
                request,
                ProcessSkillResponse.class,
                TIMEOUT);
    }
}
