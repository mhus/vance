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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Handles the server-pushed {@code switch-to} frame — Eddie's
 * {@code MEDIATE} action (or any future flow that wants to redirect
 * the client to a different session) tells us to switch the WS to a
 * different session.
 *
 * <p>Flow:
 * <ol>
 *   <li>Remember the current session id on the back-stack so
 *       {@code /hub} can later return to it.</li>
 *   <li>{@code session-unbind} the current session, then
 *       {@code session-resume} the target on the same WS. Same
 *       semantic result as the web client's close+reopen dance, but
 *       skips the WS handshake — and crucially avoids re-firing
 *       {@code WelcomeHandler.autoBootstrap}, which would otherwise
 *       race the resume and try to create a fresh session.</li>
 *   <li>Bind the session service to the new target.</li>
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

    /**
     * {@code @Lazy} on {@link ConnectionService} breaks the cycle:
     * ConnectionService → MessageDispatcher → List&lt;MessageHandler&gt;
     * → SwitchToHandler → ConnectionService. Same trick as
     * {@code WelcomeHandler}.
     */
    public SwitchToHandler(@Lazy ConnectionService connection,
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

        // Echo Eddie's announcement before the switch so the user sees
        // the context for the impending silence.
        String announcement = data.getVoiceAnnouncement();
        if (announcement != null && !announcement.isBlank()) {
            terminal.info(announcement);
        }

        // The unbind+resume round-trips are synchronous request/reply
        // pairs, but this handler runs on the MessageDispatcher's
        // receive thread — that thread is also what completes
        // pendingReplies, so a synchronous request from here would
        // deadlock on its own response. Hand the work to a fresh
        // thread; AutoBootstrapService does the same.
        String label = data.getTargetProjectId() != null && !data.getTargetProjectId().isBlank()
                ? data.getTargetProjectId()
                : (data.getTargetProcessName() != null ? data.getTargetProcessName() : target);
        Thread worker = new Thread(() -> runSwitch(target, label), "vance-foot-switch");
        worker.setDaemon(true);
        worker.start();
    }

    private void runSwitch(String target, String label) {
        try {
            // session-unbind on the same WS — quick round-trip, no
            // disconnect / reconnect / welcome / auto-bootstrap chain.
            connection.request(
                    MessageType.SESSION_UNBIND,
                    null, Void.class, Duration.ofSeconds(10));
            sessions.clear();
            // Then resume on the target. SessionResumeHandler requires
            // a session-less connection, which the unbind above
            // guaranteed.
            SessionResumeResponse resp = connection.request(
                    MessageType.SESSION_RESUME,
                    SessionResumeRequest.builder().sessionId(target).build(),
                    SessionResumeResponse.class,
                    Duration.ofSeconds(10));
            sessions.bind(resp.getSessionId(), resp.getProjectId());
            // Server tells us which process is the session's chat —
            // set it as active so the next composer line goes to it
            // without a manual /process.
            if (resp.getChatProcessName() != null && !resp.getChatProcessName().isBlank()) {
                sessions.setActiveProcess(resp.getChatProcessName());
            }
        } catch (Exception e) {
            log.error("switch-to: rebind failed for target='{}'", target, e);
            terminal.error("Switch failed: " + e);
            // Drop the back-stack — there's no consistent state to return to.
            tracker.clear();
            return;
        }
        terminal.info("Switched to " + label + " — type /hub to return.");
    }
}
