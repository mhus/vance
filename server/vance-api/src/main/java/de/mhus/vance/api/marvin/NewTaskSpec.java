package de.mhus.vance.api.marvin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Spec for one child node that NEEDS_SUBTASKS should append under
 * this node. Mirrors the v1 shape but with the reduced TaskKind set
 * (WORKER / EXPAND_FROM_DOC only — USER_INPUT goes through the
 * NEEDS_USER_INPUT pathway).
 *
 * @param goal      one-line subtask goal
 * @param taskKind  WORKER for normal children, EXPAND_FROM_DOC for
 *                  deterministic fanout
 * @param taskSpec  task-kind-specific spec (recipe override, etc.);
 *                  null/empty is fine for WORKER (inherits parent
 *                  marvin-worker)
 */
public record NewTaskSpec(
        String goal,
        TaskKind taskKind,
        @Nullable Map<String, Object> taskSpec) {

    public NewTaskSpec {
        if (taskSpec == null) {
            taskSpec = new LinkedHashMap<>();
        }
    }
}
