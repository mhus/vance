package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.session.SessionSwitchTracker;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code /hub} — return from a session Eddie switched us into. Mirror
 * of the web client's {@code /hub} slash command: pop the previous
 * session id off the {@link SessionSwitchTracker}, tear down the WS,
 * reopen it, {@code session-resume} on the remembered id.
 *
 * <p>Purely client-local — the server keeps no switch-state, so this
 * command never talks to the brain beyond the normal WS reconnect +
 * session-resume frames. No-op when no previous session is
 * remembered (we're already at the hub).
 *
 * <p>Spec: {@code specification/eddie-engine.md} §8.5.4.
 */
@Component
@Slf4j
public class HubSlashCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessions;
    private final SessionSwitchTracker tracker;
    private final ChatTerminal terminal;

    public HubSlashCommand(ConnectionService connection,
                           SessionService sessions,
                           SessionSwitchTracker tracker,
                           ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.tracker = tracker;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "hub";
    }

    @Override
    public String description() {
        return "Return to the hub session after a switch — close the current WS and resume the previously-bound session.";
    }

    @Override
    public void execute(List<String> args) {
        String target = tracker.pop();
        if (target == null) {
            terminal.info("Already at the hub — no previous session to return to.");
            return;
        }
        try {
            // Same unbind+resume pattern as SwitchToHandler — keeps the
            // WS open so we don't re-fire WelcomeHandler.autoBootstrap
            // on every /hub.
            connection.request(
                    MessageType.SESSION_UNBIND,
                    null, Void.class, Duration.ofSeconds(10));
            sessions.clear();
            SessionResumeResponse resp = connection.request(
                    MessageType.SESSION_RESUME,
                    SessionResumeRequest.builder().sessionId(target).build(),
                    SessionResumeResponse.class,
                    Duration.ofSeconds(10));
            sessions.bind(resp.getSessionId(), resp.getProjectId());
            if (resp.getChatProcessName() != null && !resp.getChatProcessName().isBlank()) {
                sessions.setActiveProcess(resp.getChatProcessName());
            }
            terminal.info("Back at the hub: " + resp.getSessionId());
        } catch (Exception e) {
            log.error("/hub: rebind to '{}' failed: {}", target, e.toString());
            terminal.error("Could not return to the hub: " + e.getMessage());
        }
    }
}
