package de.mhus.vance.foot.command;

import de.mhus.vance.api.session.SessionMetadataDto;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionMetadataPatchWsRequest;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.session.SessionService.BoundSession;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /title <text>} — set (or replace) the bound session's title.
 * Mirrors the Web-UI's title field. Pass an empty quoted string
 * ({@code /title ""}) to clear it. Beyond title there's
 * {@code /session-meta} for the full form editor.
 */
@Component
public class TitleCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessionService;
    private final ChatTerminal terminal;

    public TitleCommand(
            ConnectionService connection,
            SessionService sessionService,
            ChatTerminal terminal) {
        this.connection = connection;
        this.sessionService = sessionService;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "title";
    }

    @Override
    public String description() {
        return "Set the bound session's title — usage: /title <text>.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        BoundSession bound = sessionService.current();
        if (bound == null) {
            terminal.error("No session bound — use /session-resume or /session-bootstrap first.");
            return;
        }
        if (args.isEmpty()) {
            terminal.error("Usage: /title <text> (pass \"\" to clear).");
            return;
        }
        // Join everything after the command name — title can contain spaces.
        String title = String.join(" ", args).trim();

        SessionMetadataDto reply = connection.request(
                MessageType.SESSION_METADATA_PATCH,
                SessionMetadataPatchWsRequest.builder()
                        .sessionId(bound.sessionId())
                        .title(title)
                        .build(),
                SessionMetadataDto.class,
                Duration.ofSeconds(10));

        // Refresh the status-bar cache so the new title appears
        // immediately. Icon stays whatever it was before.
        sessionService.setMetadata(bound.sessionId(), reply.getTitle(), reply.getIcon());

        String shown = reply.getTitle() == null || reply.getTitle().isBlank()
                ? "(cleared)"
                : reply.getTitle();
        terminal.info("Title: " + shown);
    }
}
