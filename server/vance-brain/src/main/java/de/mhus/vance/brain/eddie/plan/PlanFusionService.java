package de.mhus.vance.brain.eddie.plan;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Renders a fused TodoList from Eddie's own todos plus the
 * per-worker plan-mirrors stored on her
 * {@link ThinkProcessDocument#getWorkerLinks()}. The fused list keeps
 * stable per-source prefixes on item ids so the user-client can group
 * by source without a new wire format — the regular
 * {@link TodosUpdatedNotification} carries it.
 *
 * <p>See {@code planning/eddie-plan-mode.md} §2.3.
 *
 * <h2>ID prefixes</h2>
 *
 * <ul>
 *   <li>Eddie's own todos → {@code eddie/<localId>}</li>
 *   <li>Worker todos → {@code <workerProcessName>-<workerProjectName>/<localId>}</li>
 * </ul>
 *
 * <p>Worker links without a {@code workerTodos} list are skipped; an
 * empty list is also skipped (worker has no plan currently). Eddie's
 * todos render even when she's not in plan-mode (the list is just empty).
 */
@Service
public class PlanFusionService {

    /** ID prefix for Eddie's own todos. */
    public static final String EDDIE_PREFIX = "eddie";

    /**
     * Builds a fused {@link TodosUpdatedNotification} for the given
     * Eddie process. The notification carries Eddie's own
     * {@code processId} / {@code processName} / {@code sessionId} —
     * the user-client receiving it sees a single TodoList "from
     * Eddie", with the per-source split available through the id
     * prefix on each item.
     */
    public TodosUpdatedNotification fuse(ThinkProcessDocument eddie) {
        List<TodoItem> fused = new ArrayList<>();

        // Eddie's own plan first — predictable order so the user sees
        // her own steps at the top.
        if (eddie.getTodos() != null) {
            for (TodoItem t : eddie.getTodos()) {
                fused.add(prefix(t, EDDIE_PREFIX));
            }
        }

        // Worker mirrors in registration order (insertion order of the
        // embedded array — newest links go to the bottom).
        if (eddie.getWorkerLinks() != null) {
            for (WorkerLinkSnapshot link : eddie.getWorkerLinks()) {
                if (link.getWorkerTodos() == null || link.getWorkerTodos().isEmpty()) {
                    continue;
                }
                String prefix = sourceLabel(link);
                for (TodoItem t : link.getWorkerTodos()) {
                    fused.add(prefix(t, prefix));
                }
            }
        }

        return TodosUpdatedNotification.builder()
                .processId(eddie.getId() == null ? "" : eddie.getId())
                .processName(eddie.getName())
                .sessionId(eddie.getSessionId())
                .todos(fused)
                .build();
    }

    /**
     * {@code <workerProcessName>-<workerProjectName>} when both are
     * available, falling back through the same chain as the
     * {@code <delegated_workers>} prompt-block render.
     */
    static String sourceLabel(WorkerLinkSnapshot link) {
        String name = link.getWorkerProcessName();
        String project = link.getWorkerProjectName();
        boolean hasName = name != null && !name.isBlank();
        boolean hasProject = project != null && !project.isBlank();
        if (hasName && hasProject) return name + "-" + project;
        if (hasName) return name;
        if (hasProject) return project;
        return link.getWorkerProcessId();
    }

    private static TodoItem prefix(TodoItem t, String prefix) {
        String localId = t.getId() == null ? "" : t.getId();
        return TodoItem.builder()
                .id(prefix + "/" + localId)
                .status(t.getStatus())
                .content(t.getContent())
                .activeForm(t.getActiveForm())
                .build();
    }
}
