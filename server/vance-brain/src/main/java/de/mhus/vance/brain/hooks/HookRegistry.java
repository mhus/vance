package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory index of currently active hooks per
 * {@code (tenantId, projectId)}. The dispatcher reads from it on every
 * event fan-out; the {@link HookService} updates it on project
 * bootstrap, unload, and refresh.
 *
 * <p>Concurrency: a single {@link ConcurrentHashMap} indexed by a
 * {@code "tenant|project"} key holds the per-project map of
 * {@code event → List<HookDef>}. Read-mostly access pattern — write
 * paths replace the per-project entry wholesale; reads return an
 * immutable copy of the list so the dispatcher can iterate without a
 * lock.
 */
@Component
public class HookRegistry {

    private final Map<String, Map<HookEventName, List<HookDef>>> byProject =
            new ConcurrentHashMap<>();

    /** Replace the project's hooks atomically. */
    public void replace(
            String tenantId, String projectId,
            Map<HookEventName, List<HookDef>> hooksByEvent) {
        Map<HookEventName, List<HookDef>> deepCopy = new java.util.EnumMap<>(HookEventName.class);
        for (Map.Entry<HookEventName, List<HookDef>> e : hooksByEvent.entrySet()) {
            deepCopy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        byProject.put(key(tenantId, projectId), deepCopy);
    }

    /** Drop every hook for this project. */
    public void clear(String tenantId, String projectId) {
        byProject.remove(key(tenantId, projectId));
    }

    /** Replace a single event's slot for a project (delta refresh). */
    public void replaceEvent(
            String tenantId, String projectId,
            HookEventName event, List<HookDef> defs) {
        Map<HookEventName, List<HookDef>> snap = byProject.computeIfAbsent(
                key(tenantId, projectId),
                k -> new java.util.EnumMap<>(HookEventName.class));
        if (defs.isEmpty()) {
            snap.remove(event);
        } else {
            snap.put(event, List.copyOf(defs));
        }
    }

    /** Hooks listening for this event in this project. Empty list if none. */
    public List<HookDef> hooksFor(
            String tenantId, String projectId, HookEventName event) {
        Map<HookEventName, List<HookDef>> snap = byProject.get(key(tenantId, projectId));
        if (snap == null) return List.of();
        List<HookDef> defs = snap.get(event);
        return defs == null ? List.of() : defs;
    }

    /** Every hook in this project across all events, for {@code hook_list}. */
    public List<HookDef> allFor(String tenantId, String projectId) {
        Map<HookEventName, List<HookDef>> snap = byProject.get(key(tenantId, projectId));
        if (snap == null) return List.of();
        List<HookDef> out = new ArrayList<>();
        for (Collection<HookDef> defs : snap.values()) {
            out.addAll(defs);
        }
        return out;
    }

    /** Live count — for diagnostics only. */
    public int countForProject(String tenantId, String projectId) {
        return allFor(tenantId, projectId).size();
    }

    private static String key(String tenantId, String projectId) {
        return tenantId + "|" + projectId;
    }
}
