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
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@code /share [on|off]} — owner-only multi-user toggle on the bound
 * session.
 *
 * <ul>
 *   <li>{@code /share} — show the current state (and what {@code on}
 *       / {@code off} mean).</li>
 *   <li>{@code /share on} — flip {@code allowMultipleClients} to true.
 *       Other authenticated users in the same tenant can then see and
 *       join this session.</li>
 *   <li>{@code /share off} — flip back to private. New connections
 *       from anyone other than the owner are rejected.</li>
 * </ul>
 *
 * <p>Mirrors the 👥 toggle in the Web-UI session header. See
 * {@code planning/multi-user-sessions.md} §2.1.
 */
@Component
public class ShareSlashCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessionService;
    private final ChatTerminal terminal;

    public ShareSlashCommand(
            ConnectionService connection,
            SessionService sessionService,
            ChatTerminal terminal) {
        this.connection = connection;
        this.sessionService = sessionService;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "share";
    }

    @Override
    public String description() {
        return "Toggle multi-user sharing on the bound session — usage: /share [on|off].";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.enumOf("mode", List.of("on", "off")));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        BoundSession bound = sessionService.current();
        if (bound == null) {
            terminal.error("No session bound — use /session-resume or /session-bootstrap first.");
            return;
        }

        SessionMetadataPatchWsRequest.SessionMetadataPatchWsRequestBuilder builder =
                SessionMetadataPatchWsRequest.builder().sessionId(bound.sessionId());

        if (args.isEmpty()) {
            // Status-only read: a no-op patch still echoes the current
            // metadata back through the handler's reply path.
        } else {
            String mode = args.get(0).toLowerCase(Locale.ROOT);
            switch (mode) {
                case "on" -> builder.allowMultipleClients(true);
                case "off" -> builder.allowMultipleClients(false);
                default -> {
                    terminal.error("Usage: /share [on|off]. Run /share without arguments to query.");
                    return;
                }
            }
        }

        SessionMetadataDto reply = connection.request(
                MessageType.SESSION_METADATA_PATCH,
                builder.build(),
                SessionMetadataDto.class,
                Duration.ofSeconds(10));

        if (reply.isAllowMultipleClients()) {
            terminal.info("Session is SHARED — anyone in this tenant can join with @ai mentions.");
        } else {
            terminal.info("Session is PRIVATE — only you can connect.");
        }
    }
}
