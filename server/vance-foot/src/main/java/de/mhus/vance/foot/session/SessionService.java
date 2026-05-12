package de.mhus.vance.foot.session;

import de.mhus.vance.foot.agent.ClientAgentDocService;
import de.mhus.vance.foot.command.SuggestionCache;
import de.mhus.vance.foot.tools.ClientToolService;
import de.mhus.vance.foot.tools.exec.FootExecEventDispatcher;
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
 * line repaints, and the {@link ClientToolService} so client-side tool
 * registrations are pushed to the brain after each (re-)bind. Both
 * dependencies are resolved lazily via {@link ObjectProvider} to avoid
 * fighting Spring on construction order with services that themselves
 * read from this one.
 */
@Service
public class SessionService {

    private final AtomicReference<@Nullable BoundSession> current = new AtomicReference<>();
    private final AtomicReference<@Nullable String> activeProcess = new AtomicReference<>();
    private final ObjectProvider<StatusBar> statusBar;
    private final ObjectProvider<ClientToolService> clientToolService;
    private final ObjectProvider<ClientAgentDocService> clientAgentDocService;
    private final ObjectProvider<SuggestionCache> suggestionCache;
    private final ObjectProvider<FootExecEventDispatcher> execEventDispatcher;

    public SessionService(
            ObjectProvider<StatusBar> statusBar,
            ObjectProvider<ClientToolService> clientToolService,
            ObjectProvider<ClientAgentDocService> clientAgentDocService,
            ObjectProvider<SuggestionCache> suggestionCache,
            ObjectProvider<FootExecEventDispatcher> execEventDispatcher) {
        this.statusBar = statusBar;
        this.clientToolService = clientToolService;
        this.clientAgentDocService = clientAgentDocService;
        this.suggestionCache = suggestionCache;
        this.execEventDispatcher = execEventDispatcher;
    }

    public @Nullable BoundSession current() {
        return current.get();
    }

    public void bind(String sessionId, String projectId) {
        current.set(new BoundSession(sessionId, projectId, null, null));
        notifyStatusBar();
        notifyClientTools();
        uploadClientAgentDoc();
        invalidateSuggestions();
        publishExecSnapshot();
    }

    /**
     * Updates the cached title / icon of the currently bound session.
     * No-op when the bound session id does not match, or when no
     * session is bound. Triggers a status-bar repaint so the prompt
     * picks up the new metadata immediately.
     */
    public void setMetadata(String sessionId, @Nullable String title, @Nullable String icon) {
        BoundSession existing = current.get();
        if (existing == null || !existing.sessionId().equals(sessionId)) return;
        current.set(new BoundSession(
                existing.sessionId(), existing.projectId(), title, icon));
        notifyStatusBar();
    }

    public void clear() {
        current.set(null);
        activeProcess.set(null);
        notifyStatusBar();
        invalidateSuggestions();
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

    private void notifyClientTools() {
        ClientToolService cts = clientToolService.getIfAvailable();
        if (cts != null) {
            cts.registerAll();
        }
    }

    private void uploadClientAgentDoc() {
        ClientAgentDocService cads = clientAgentDocService.getIfAvailable();
        if (cads != null) {
            cads.uploadIfPresent();
        }
    }

    private void invalidateSuggestions() {
        SuggestionCache cache = suggestionCache.getIfAvailable();
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    private void publishExecSnapshot() {
        FootExecEventDispatcher d = execEventDispatcher.getIfAvailable();
        if (d != null) {
            d.publishCurrentSnapshot();
        }
    }

    /**
     * The session-id and project-id the Brain confirmed when binding,
     * plus the cached title / icon. The metadata fields are populated
     * lazily by {@link #setMetadata}; they remain {@code null} until
     * a path that reads the session-list (or runs a metadata patch)
     * fills them in.
     */
    public record BoundSession(
            String sessionId,
            String projectId,
            @Nullable String title,
            @Nullable String icon) {
    }
}
