package de.mhus.vance.foot.remote;

import de.mhus.vance.api.ws.RemoteClientPrompt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Leaf bean holding "is anybody watching this foot from outside, and what is it
 * waiting on".
 *
 * <p>Deliberately dependency-free. The two consumers sit at opposite ends of the
 * wiring — {@link RemoteControlService} (which needs the connection) and
 * {@code PermissionPrompt} (which the connection path reaches into for tool
 * calls) — so putting the flag on either of them would close a Spring cycle.
 * A leaf with no collaborators cannot.
 *
 * <p>The prompt publisher is injected by {@link RemoteControlService} at startup
 * rather than autowired here, for the same reason.
 */
@Component
@Slf4j
public class RemoteWatcherState {

    /** Attached watcher editor-ids. A set, because two devices may watch at once. */
    private final Set<String> watchers = ConcurrentHashMap.newKeySet();

    private final AtomicReference<@Nullable PromptPublisher> publisher = new AtomicReference<>();

    /** Sink for prompt frames. Implemented by {@link RemoteControlService}. */
    public interface PromptPublisher {
        void publish(RemoteClientPrompt prompt);
    }

    public void setPublisher(@Nullable PromptPublisher p) {
        publisher.set(p);
    }

    public void addWatcher(String watcherId) {
        watchers.add(watcherId);
    }

    public void removeWatcher(String watcherId) {
        watchers.remove(watcherId);
    }

    public void clearWatchers() {
        watchers.clear();
    }

    /**
     * Whether at least one remote watcher is attached. Drives two things: the
     * output push (nobody watching ⇒ nothing published) and the interactive
     * prompt timeout (someone watching ⇒ a human may be minutes away, so the
     * 25 s local default would deny before the phone even buzzed).
     */
    public boolean hasWatchers() {
        return !watchers.isEmpty();
    }

    public int watcherCount() {
        return watchers.size();
    }

    /**
     * Publishes a prompt to attached watchers. No-op when nothing is wired or
     * nobody is watching — the caller (a blocking prompt on the tool-call
     * thread) must never depend on this succeeding.
     */
    public void publishPrompt(RemoteClientPrompt prompt) {
        PromptPublisher p = publisher.get();
        if (p == null || watchers.isEmpty()) {
            return;
        }
        try {
            p.publish(prompt);
        } catch (RuntimeException e) {
            log.trace("remote prompt publish failed: {}", e.toString());
        }
    }
}
