package de.mhus.vance.foot.remote;

import de.mhus.vance.api.ws.RemoteClientState;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.BusyIndicator;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import de.mhus.vance.foot.ui.UiMode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for "what is this foot doing right now".
 *
 * <p>Extracted because two transports need the same answer: the local debug
 * REST server ({@code GET /debug/state}) and the remote-control channel. Before
 * the split each built its own map, which is exactly how the two views drift
 * apart — one gains a field, the other keeps answering the old shape.
 *
 * <p>The snapshot covers what the <em>pinned</em> terminal UI shows: connection,
 * bound session, which surface owns the TTY, whether a turn is running. None of
 * that appears in the line stream, so a remote watcher has no other way to
 * learn it.
 */
@Service
public class FootStateService {

    private final FootConfig config;
    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;
    private final InterfaceService ui;
    private final BusyIndicator busy;
    private final RemoteClientIdentity identity;
    private final RemoteControlGate gate;

    public FootStateService(FootConfig config,
                            @Lazy ConnectionService connection,
                            SessionService sessions,
                            ChatTerminal terminal,
                            InterfaceService ui,
                            BusyIndicator busy,
                            RemoteClientIdentity identity,
                            RemoteControlGate gate) {
        this.config = config;
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
        this.ui = ui;
        this.busy = busy;
        this.identity = identity;
        this.gate = gate;
    }

    /** Current state of this client, ready to send or serialise. */
    public RemoteClientState snapshot() {
        SessionService.BoundSession bound = sessions.current();
        InputGate input = inputGate();
        return RemoteClientState.builder()
                .clientId(identity.clientId())
                .connection(connection.state().name())
                .sessionId(bound == null ? null : bound.sessionId())
                .projectId(bound == null ? null : bound.projectId())
                .uiMode(ui.mode().name())
                .verbosity(terminal.threshold().name())
                .busy(busy.isBusy())
                .lastSeq(terminal.lastSeq())
                .acceptingInput(input.accepted())
                .inputBlockedReason(input.reason())
                .build();
    }

    /**
     * Whether a remote input line would be executed right now, and if not, the
     * reason in words the watcher can show verbatim.
     *
     * <p>Two independent gates. A Lanterna excursion owns the TTY exclusively
     * (JLine is paused, the REPL is not reading), so a submitted line has
     * nowhere to go — and a remotely triggered fullscreen would hijack the
     * screen of a machine nobody is sitting at. The mode gate is the
     * authorization side: {@code ask} means output is visible but the hand
     * stays local until someone at the terminal says otherwise.
     */
    public InputGate inputGate() {
        if (!gate.isEnabled()) {
            return new InputGate(false, "Remote control is off on this client (vance.remote.mode=off)");
        }
        if (!gate.isInputAllowed()) {
            return new InputGate(false, "Waiting for local approval — run /remote allow at the client");
        }
        if (ui.mode() == UiMode.FULLSCREEN) {
            return new InputGate(false, "Client is in a fullscreen UI — remote input is locked");
        }
        return new InputGate(true, null);
    }

    /** Configured prompt timeout for the case where a remote watcher is present. */
    public long remotePromptTimeoutMs() {
        return Math.max(1_000L, config.getRemote().getPromptTimeout().toMillis());
    }

    /** Verdict of {@link #inputGate()}: accepted, plus why not when refused. */
    public record InputGate(boolean accepted, @org.jspecify.annotations.Nullable String reason) {}
}
