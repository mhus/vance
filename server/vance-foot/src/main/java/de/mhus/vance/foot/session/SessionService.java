package de.mhus.vance.foot.session;

import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
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
 */
@Service
public class SessionService {

    private final AtomicReference<@Nullable BoundSession> current = new AtomicReference<>();
    private final AtomicReference<@Nullable String> activeProcess = new AtomicReference<>();

    public @Nullable BoundSession current() {
        return current.get();
    }

    public void bind(String sessionId, String projectId) {
        current.set(new BoundSession(sessionId, projectId));
    }

    public void clear() {
        current.set(null);
        activeProcess.set(null);
    }

    public @Nullable String activeProcess() {
        return activeProcess.get();
    }

    public void setActiveProcess(@Nullable String processName) {
        activeProcess.set(processName);
    }

    /** The session-id and project-id the Brain confirmed when binding. */
    public record BoundSession(String sessionId, String projectId) {}
}
