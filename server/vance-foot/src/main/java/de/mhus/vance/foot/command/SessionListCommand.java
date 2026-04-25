package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionListRequest;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code /session-list [projectId]} — lists sessions of the current user,
 * optionally filtered by project.
 */
@Component
public class SessionListCommand implements SlashCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public SessionListCommand(ConnectionService connection, ChatTerminal terminal) {
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "session-list";
    }

    @Override
    public String description() {
        return "List sessions for the current user. Optional: [projectId].";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.size() > 1) {
            terminal.error("Usage: /session-list [projectId]");
            return;
        }
        String projectId = args.isEmpty() ? null : args.get(0);
        SessionListResponse response = connection.request(
                MessageType.SESSION_LIST,
                SessionListRequest.builder().projectId(projectId).build(),
                SessionListResponse.class,
                Duration.ofSeconds(10));

        if (response.getSessions() == null || response.getSessions().isEmpty()) {
            terminal.info("No sessions.");
            return;
        }
        terminal.info(String.format("%-20s %-10s %-11s %-20s %s",
                "PROJECT", "STATUS", "LAST SEEN", "NAME", "SESSION"));
        for (SessionSummary s : response.getSessions()) {
            String sessionCell = s.getSessionId() + (s.isBound() ? " (bound)" : "");
            terminal.info(String.format("%-20s %-10s %-11s %-20s %s",
                    truncate(Objects.toString(s.getProjectId(), ""), 20),
                    truncate(Objects.toString(s.getStatus(), ""), 10),
                    TIME.format(Instant.ofEpochMilli(s.getLastActivityAt())),
                    truncate(Objects.toString(s.getDisplayName(), ""), 20),
                    sessionCell));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
