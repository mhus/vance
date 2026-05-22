package de.mhus.vance.foot.command;

import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code /hub} — ends an Eddie-mediated direct-conversation with a
 * worker session and bounces the foot client back to Eddie's hub
 * session. Sends a single {@code mediation-end} frame; the brain-side
 * {@code MediationEndHandler} intercepts it (the worker never sees
 * the frame) and replies with a {@code mediate-handover} pointing at
 * Eddie's session, which the foot's existing handover handler turns
 * into a {@code session-resume}.
 *
 * <p>No-op when no mediation is active — the brain-side handler
 * silently acknowledges in that case, so the user just sees a quick
 * "no mediation in progress" line in the terminal.
 *
 * <p>See {@code specification/eddie-engine.md} §8.5.5 +
 * {@code engine-message-routing.md} §4.1.2.
 */
@Component
@Slf4j
public class HubSlashCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public HubSlashCommand(ConnectionService connection, ChatTerminal terminal) {
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "hub";
    }

    @Override
    public String description() {
        return "Return to Eddie's hub session — ends a direct worker conversation Eddie handed you off to.";
    }

    @Override
    public void execute(List<String> args) {
        // Foot's switch-to / switch-back handlers are not wired yet.
        // Once they are, /hub becomes a local close+reopen on the
        // remembered hub sessionId — same flow as the web client.
        terminal.warn("/hub is not implemented in foot yet — switch back via /session-resume or restart the client.");
    }
}
