package de.mhus.vance.foot.session;

import de.mhus.vance.foot.ui.StatusBar;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Tracks the session bound to the current connection, plus which think-process
 * within that session is "active" — i.e. the implicit target for free-text
 * chat input from the REPL. Both pieces of state are cleared on disconnect
 * or session-unbind.
 *
 * <p>The active-process selection is purely client-side. The Brain has no
 * concept of "active" — every {@code process-steer} carries the target
 * processName explicitly. The active-process is just a REPL convenience so
 * users do not have to type the process name on every line.
 *
 * <p>Mutations notify the {@link StatusBar} so the bottom-pinned status
 * line repaints. The dependency is resolved lazily via {@link ObjectProvider}
 * so we don't fight Spring on construction order with the StatusBar that
 * itself reads from this service.
 */
@Service
public class SessionService {

    private final AtomicReference<@Nullable BoundSession> current = new AtomicReference<>();
    private final AtomicReference<@Nullable String> activeProcess = new AtomicReference<>();
    private final ObjectProvider<StatusBar> statusBar;

    public SessionService(ObjectProvider<StatusBar> statusBar) {
        this.statusBar = statusBar;
    }

    public @Nullable BoundSession current() {
        return current.get();
    }

    public void bind(String sessionId, String projectId) {
        current.set(new BoundSession(sessionId, projectId));
        notifyStatusBar();
    }

    public void clear() {
        current.set(null);
        activeProcess.set(null);
        notifyStatusBar();
    }

    public @Nullable String activeProcess() {
        return activeProcess.get();
    }

    public void setActiveProcess(@Nullable String processName) {
        activeProcess.set(processName);
        notifyStatusBar();
    }

    private void notifyStatusBar() {
        StatusBar bar = statusBar.getIfAvailable();
        if (bar != null) {
            bar.refresh();
        }
    }

    /** The session-id and project-id the Brain confirmed when binding. */
    public record BoundSession(String sessionId, String projectId) {}
}
