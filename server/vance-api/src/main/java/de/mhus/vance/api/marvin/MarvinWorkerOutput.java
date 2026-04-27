package de.mhus.vance.api.marvin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Structured output schema a Marvin worker emits at the end of its
 * turn. The worker is instructed (via the {@code marvin-worker}
 * recipe / steer-postfix) to terminate every reply with a JSON
 * block of this shape — Marvin parses it and routes the worker
 * node accordingly. See {@code specification/marvin-engine.md} §5a.
 *
 * <p>Field semantics depend on {@link #outcome}:
 * <ul>
 *   <li>{@link WorkerOutcome#DONE} — {@link #result} required;
 *       {@link #reason} optional one-liner.</li>
 *   <li>{@link WorkerOutcome#NEEDS_SUBTASKS} — {@link #newTasks}
 *       non-empty; {@link #result} optional partial result;
 *       {@link #reason} explains the decomposition.</li>
 *   <li>{@link WorkerOutcome#NEEDS_USER_INPUT} — {@link #userInput}
 *       required; {@link #result} optional partial result.</li>
 *   <li>{@link WorkerOutcome#BLOCKED_BY_PROBLEM} —
 *       {@link #problem} required; {@link #reason} optional.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarvinWorkerOutput {

    private WorkerOutcome outcome = WorkerOutcome.DONE;

    /** Markdown answer (DONE) or partial-result so far
     *  (NEEDS_SUBTASKS / NEEDS_USER_INPUT). {@code null} only when
     *  outcome is BLOCKED_BY_PROBLEM. */
    private @Nullable String result;

    /** Subtask specs for {@link WorkerOutcome#NEEDS_SUBTASKS}. */
    @Builder.Default
    private List<NewTaskSpec> newTasks = new ArrayList<>();

    /** Inbox-item spec for {@link WorkerOutcome#NEEDS_USER_INPUT}. */
    private @Nullable UserInputSpec userInput;

    /** Short statement of the blocking problem
     *  (BLOCKED_BY_PROBLEM). */
    private @Nullable String problem;

    /** Free-form one-liner explaining the chosen outcome —
     *  always recommended, optional. */
    private @Nullable String reason;

    /** Subtask spec mirroring the PLAN-output children format. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewTaskSpec {
        private String goal = "";
        private TaskKind taskKind = TaskKind.WORKER;
        @Builder.Default
        private Map<String, Object> taskSpec = new LinkedHashMap<>();
    }

    /** User-input spec; mirrors the inbox-item create shape. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInputSpec {
        /** Inbox item type — DECISION, FEEDBACK, APPROVAL, ... */
        private String type = "FEEDBACK";
        private String title = "";
        private @Nullable String body;
        private @Nullable String criticality;
        @Builder.Default
        private Map<String, Object> payload = new LinkedHashMap<>();
    }
}
