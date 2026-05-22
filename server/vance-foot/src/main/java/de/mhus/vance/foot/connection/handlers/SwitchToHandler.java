package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.eddie.SwitchToNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.session.SessionSwitchTracker;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * Handles the server-pushed {@code switch-to} frame — Eddie's
 * {@code MEDIATE} action (or any future flow that wants to redirect
 * the client to a different session) tells us to drop the current
 * WebSocket and open a new one bound to the target session.
 *
 * <p>Flow mirrors the web client (see {@code ChatApp.onSwitchTo}):
 * <ol>
 *   <li>Remember the current session id on the back-stack so
 *       {@code /hub} can later return to it.</li>
 *   <li>Tear down the WS (close + reopen — clean break, no
 *       session-unbind dance).</li>
 *   <li>{@code session-resume} on the target id, bind the session
 *       service.</li>
 *   <li>Echo a one-line "switched to X — type /hub to return" hint
 *       to the terminal so the user knows where they ended up.</li>
 * </ol>
 *
 * <p>Spec: {@code specification/eddie-engine.md} §8.5,
 * {@code specification/engine-message-routing.md} §4.1.2.
 */
@Component
@Slf4j
public class SwitchToHandler implements MessageHandler {

    private final ConnectionService connection;
    private final SessionService sessions;
    private final SessionSwitchTracker tracker;
    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public SwitchToHandler(ConnectionService connection,
                           SessionService sessions,
                           SessionSwitchTracker tracker,
                           ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.tracker = tracker;
        this.terminal = terminal;
    }

    @Override
    public String messageType() {
        return MessageType.SWITCH_TO;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        SwitchToNotification data;
        try {
            data = json.convertValue(envelope.getData(), SwitchToNotification.class);
        } catch (RuntimeException e) {
            log.warn("switch-to: malformed payload — {}", e.toString());
            return;
        }
        String target = data.getTargetSessionId();
        if (target == null || target.isBlank()) {
            log.warn("switch-to: no targetSessionId in payload");
            return;
        }

        // Push the current session id onto the back-stack so /hub can
        // return. Null is fine (we may not have been bound yet).
        SessionService.BoundSession current = sessions.current();
        tracker.push(current == null ? null : current.sessionId());

        // Echo Eddie's announcement before the disconnect so the user
        // sees the context for the impending terminal-line silence.
        String announcement = data.getVoiceAnnouncement();
        if (announcement != null && !announcement.isBlank()) {
            terminal.info(announcement);
        }

        try {
            connection.disconnect("switching to " + target);
            connection.connect();
            SessionResumeResponse resp = connection.request(
                    MessageType.SESSION_RESUME,
                    SessionResumeRequest.builder().sessionId(target).build(),
                    SessionResumeResponse.class,
                    Duration.ofSeconds(10));
            sessions.bind(resp.getSessionId(), resp.getProjectId());
        } catch (Exception e) {
            log.error("switch-to: rebind failed for target='{}': {}", target, e.toString());
            terminal.error("Switch failed: " + e.getMessage());
            // Drop the back-stack — there's no consistent state to return to.
            tracker.clear();
            return;
        }

        String label = data.getTargetProjectId() != null && !data.getTargetProjectId().isBlank()
                ? data.getTargetProjectId()
                : (data.getTargetProcessName() != null ? data.getTargetProcessName() : target);
        terminal.info("Switched to " + label + " — type /hub to return.");
    }
}
