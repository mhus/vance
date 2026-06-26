package de.mhus.vance.foot.command;

import de.mhus.vance.api.session.SessionParticipantDto;
import de.mhus.vance.api.session.SessionRosterData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.session.SessionService.BoundSession;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@code /who} — list the participants currently bound to the
 * session. Mirrors the Web-UI's {@code /who} slash command. See
 * {@code planning/multi-user-sessions.md} §7.
 */
@Component
public class WhoSlashCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessionService;
    private final ChatTerminal terminal;

    public WhoSlashCommand(
            ConnectionService connection,
            SessionService sessionService,
            ChatTerminal terminal) {
        this.connection = connection;
        this.sessionService = sessionService;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "who";
    }

    @Override
    public String description() {
        return "Show participants currently bound to the session.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        BoundSession bound = sessionService.current();
        if (bound == null) {
            terminal.error("No session bound.");
            return;
        }
        SessionRosterData reply = connection.request(
                MessageType.SESSION_WHO,
                null,
                SessionRosterData.class,
                Duration.ofSeconds(10));
        List<SessionParticipantDto> participants = reply.getParticipants();
        if (participants == null || participants.isEmpty()) {
            terminal.info("In the room: nobody");
            return;
        }
        String names = participants.stream()
                .map(p -> {
                    String name = p.getDisplayName();
                    return name == null || name.isBlank() ? p.getUserId() : name;
                })
                .collect(Collectors.joining(", "));
        terminal.info("In the room: " + names);
    }
}
