package de.mhus.vance.foot.ui;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.TodoItem;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Tracks per-process Plan-Mode state on the foot client: the current
 * {@link ProcessMode} and the most recent TodoList. Driven by the
 * {@code process-mode-changed} and {@code todos-updated} WebSocket
 * notifications.
 *
 * <p>Two consumers read from here:
 * <ul>
 *   <li>{@link StatusBar} — pins the TodoList as a persistent panel
 *       above the prompt while it is non-empty.</li>
 *   <li>{@link ChatRepl} — appends a mode tag to the prompt
 *       ({@code vance(SID, planning)>}) for the active process.</li>
 * </ul>
 *
 * <p>The state collapses to "no panel, no tag" when the active process
 * is back in {@link ProcessMode#NORMAL} — {@link #setMode setMode} drops
 * the entry rather than persisting a stale {@code NORMAL}.
 */
@Component
public class PlanModeState {

    private final Map<String, ProcessMode> modes = new ConcurrentHashMap<>();
    private final Map<String, List<TodoItem>> todos = new ConcurrentHashMap<>();
    private final ObjectProvider<StatusBar> statusBar;

    public PlanModeState(ObjectProvider<StatusBar> statusBar) {
        this.statusBar = statusBar;
    }

    public ProcessMode mode(@Nullable String processName) {
        if (processName == null) return ProcessMode.NORMAL;
        return modes.getOrDefault(processName, ProcessMode.NORMAL);
    }

    public List<TodoItem> todos(@Nullable String processName) {
        if (processName == null) return List.of();
        return todos.getOrDefault(processName, List.of());
    }

    public void setMode(String processName, ProcessMode mode) {
        if (mode == ProcessMode.NORMAL) {
            // Falling back to NORMAL means the plan/execution cycle is
            // over — drop both mode and todos so the panel disappears.
            modes.remove(processName);
            todos.remove(processName);
        } else {
            modes.put(processName, mode);
        }
        notifyChanged();
    }

    public void setTodos(String processName, @Nullable List<TodoItem> items) {
        if (items == null || items.isEmpty()) {
            todos.remove(processName);
        } else {
            todos.put(processName, List.copyOf(items));
        }
        notifyChanged();
    }

    private void notifyChanged() {
        StatusBar bar = statusBar.getIfAvailable();
        if (bar != null) {
            bar.refresh();
        }
    }
}
