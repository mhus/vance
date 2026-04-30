package de.mhus.vance.foot.command;

import de.mhus.vance.api.skills.ActiveSkillRefDto;
import de.mhus.vance.api.skills.ProcessSkillCommand;
import de.mhus.vance.api.skills.ProcessSkillRequest;
import de.mhus.vance.api.skills.ProcessSkillResponse;
import de.mhus.vance.api.skills.SkillSummaryDto;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared back-end for the three skill-related slash commands
 * ({@link SkillSlashCommand}, {@link SkillListSlashCommand},
 * {@link SkillClearSlashCommand}). Centralises the active-process
 * resolution, the WebSocket round-trips, and the terminal rendering so
 * the slash-command shells stay one-liners.
 */
@Component
public class SkillCommandHelper {

    private static final Duration SKILL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STEER_TIMEOUT = Duration.ofSeconds(60);

    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final SessionService sessions;

    public SkillCommandHelper(
            ConnectionService connection,
            ChatTerminal terminal,
            SessionService sessions) {
        this.connection = connection;
        this.terminal = terminal;
        this.sessions = sessions;
    }

    /** Returns the active process name or {@code null} after printing the error. */
    public @Nullable String requireActiveProcess() {
        String processName = sessions.activeProcess();
        if (processName == null || processName.isBlank()) {
            terminal.error("No active process — use /process-activate first");
            return null;
        }
        return processName;
    }

    public void list(String processName) throws Exception {
        ProcessSkillResponse response = sendSkillRequest(ProcessSkillRequest.builder()
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
        for (SkillSummaryDto dto : response.getAvailableSkills()) {
            String marker = activeNames.contains(dto.getName()) ? "  ✓ " : "    ";
            terminal.info(marker + dto.getName()
                    + "  [" + dto.getSource() + "]"
                    + "  — " + dto.getDescription());
        }
    }

    /**
     * @param skillName when {@code null} or blank, clears all non-recipe
     *                  skills; otherwise clears the named skill only
     */
    public void clear(String processName, @Nullable String skillName) throws Exception {
        if (skillName == null || skillName.isBlank()) {
            ProcessSkillResponse response = sendSkillRequest(ProcessSkillRequest.builder()
                    .processName(processName)
                    .command(ProcessSkillCommand.CLEAR_ALL)
                    .build());
            terminal.info("cleared. active skills now: " + response.getActiveSkills().size());
            return;
        }
        ProcessSkillResponse response = sendSkillRequest(ProcessSkillRequest.builder()
                .processName(processName)
                .command(ProcessSkillCommand.CLEAR)
                .skillName(skillName)
                .build());
        terminal.info("cleared '" + skillName + "'. active skills now: "
                + response.getActiveSkills().size());
    }

    /**
     * Activates {@code skillName}; if {@code trailingMessage} is
     * non-blank, sends it as a follow-up chat message so the
     * freshly-activated skill applies to that turn.
     */
    public void activate(
            String processName,
            String skillName,
            boolean oneShot,
            List<String> trailingMessageTokens) throws Exception {
        ProcessSkillResponse response = sendSkillRequest(ProcessSkillRequest.builder()
                .processName(processName)
                .command(ProcessSkillCommand.ACTIVATE)
                .skillName(skillName)
                .oneShot(oneShot)
                .build());
        terminal.info("→ skill '" + skillName + "' activated"
                + (oneShot ? " (once)" : "")
                + ". active skills now: " + response.getActiveSkills().size());

        if (trailingMessageTokens == null || trailingMessageTokens.isEmpty()) {
            return;
        }
        String content = String.join(" ", trailingMessageTokens);
        ProcessSteerResponse steer = connection.request(
                MessageType.PROCESS_STEER,
                ProcessSteerRequest.builder()
                        .processName(processName)
                        .content(content)
                        .build(),
                ProcessSteerResponse.class,
                STEER_TIMEOUT);
        terminal.info("→ steered " + steer.getProcessName()
                + " (status=" + steer.getStatus() + ")");
    }

    /**
     * Splits {@code [--once] [args...]} into an oneShot flag plus the
     * remaining tokens (the trailing chat message, if any).
     */
    public ParsedActivateArgs parseActivateArgs(List<String> args) {
        boolean oneShot = false;
        List<String> remaining = new ArrayList<>(args);
        for (int i = 0; i < remaining.size(); i++) {
            if ("--once".equals(remaining.get(i))) {
                oneShot = true;
                remaining.remove(i);
                break;
            }
        }
        return new ParsedActivateArgs(oneShot, remaining);
    }

    public record ParsedActivateArgs(boolean oneShot, List<String> trailingTokens) {
    }

    private ProcessSkillResponse sendSkillRequest(ProcessSkillRequest request) throws Exception {
        return connection.request(
                MessageType.PROCESS_SKILL,
                request,
                ProcessSkillResponse.class,
                SKILL_TIMEOUT);
    }
}
