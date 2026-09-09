package de.mhus.vance.brain.benjy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Benjy's persisted machine state — the authority behind the todos
 * projection (see {@code planning/benjy-engine.md} §9/§9a).
 *
 * <p>Stored as a serialized map under {@code engineParams.benjyState}
 * (same persistence form as Zaphod's {@code zaphodState}) and round-tripped
 * through Jackson. Machine data lives here and is <b>never</b> subject to
 * memory compaction; the <em>knowledge</em> layer (route decisions, facts,
 * dialogue) lives in the process's own chat history as journal entries
 * plus user/assistant turns (§4b/§22) and may compact.
 *
 * <p>Crash-consistency contract (§4a "Persistenz-Ordnung"): every queue
 * transition is persisted <b>first</b> via
 * {@code ThinkProcessService.replaceEngineParams}, before the derived side
 * effects (todos projection, journal append, worker spawn) run. Queue and
 * items are the source; todos and journal regenerate from it.
 */
@Data
public class BenjyState {

    /** The raw goal text as it arrived (spawn task / first user message). */
    private @Nullable String goal;

    /** The interpreted goal as the interpret call phrased it. */
    private @Nullable String interpretedGoal;

    /** Active chain template — one of {@code BenjyFeatureConfig#TASK_TYPES}. */
    private String taskType = BenjyFeatureConfig.TASK_TYPE_INFO;

    /** Acceptance criteria — "well solved" is a checkable list, not a gut feeling. */
    private List<Criterion> criteria = new ArrayList<>();

    /** Work items — the fachliche layer; each expands to a task chain. */
    private List<Item> items = new ArrayList<>();

    /** The task queue — the operational layer; each LLM call, spawn and check is one task. */
    private List<QueuedTask> queue = new ArrayList<>();

    /** The currently running do-task's worker, or {@code null} when none. */
    private @Nullable InFlight inFlight;

    /** An open ask_parent question this process is BLOCKED on, or {@code null}. */
    private @Nullable String pendingQuestion;

    /** Whether the reflect gate already ran (it has precedence over route's done). */
    private boolean reflected;

    /** Final report — set by the done task, read by {@code summarizeForParent}. */
    private @Nullable String finalReport;

    private Counters counters = new Counters();

    /** Stuck detection: last route decision key + how often it repeated unchanged. */
    private @Nullable String lastRouteKey;

    private int sameRouteCount;

    /** Start of the current work phase — wallclock safety nets measure from here. */
    private Instant phaseStartedAt = Instant.now();

    /** Monotonic per-process task id seed. */
    private int nextTaskId = 1;

    /** A single acceptance criterion. {@code sourceRef} traces it back to a criteria source doc. */
    @Data
    public static class Criterion {
        private String id;
        private String text;
        private @Nullable String sourceRef;
        /** pending | pass | fail */
        private String status = "pending";

        private @Nullable String evidence;

        public static Criterion of(String id, String text, @Nullable String sourceRef) {
            Criterion c = new Criterion();
            c.setId(id);
            c.setText(text);
            c.setSourceRef(sourceRef);
            return c;
        }
    }

    /**
     * A work item. {@code id} is the server-assigned todo id (Frankie
     * convention: sequential, never reused) so the todos projection can
     * key on the same id.
     */
    @Data
    public static class Item {
        private String id;
        private String content;
        /** pending | in_progress | completed | failed */
        private String status = "pending";

        private int attempts;
        private List<String> facts = new ArrayList<>();
        private @Nullable String lastResult;

        public static Item of(String id, String content) {
            Item i = new Item();
            i.setId(id);
            i.setContent(content);
            return i;
        }

        public boolean isTerminal() {
            return "completed".equals(status) || "failed".equals(status);
        }

        /** Bounded fact accumulation — keeps the digest small for small models. */
        public void addFact(String fact) {
            facts.add(fact);
            while (facts.size() > 12) {
                facts.removeFirst();
            }
        }

        /** The last worker reply for this item — evaluate reads it; bounded on set. */
        public void setLastResult(@Nullable String result) {
            lastResult = result == null || result.length() <= 4000 ? result : result.substring(0, 4000);
        }
    }

    /** One queued task: an LLM call, a worker spawn, a mechanical check or a mechanics step. */
    @Data
    public static class QueuedTask {
        private String id;
        /** One of the {@code BenjyTaskTypes} constants. */
        private String type;

        private @Nullable String itemRef;
        private Map<String, Object> payload = new LinkedHashMap<>();

        public static QueuedTask of(String id, String type, @Nullable String itemRef) {
            QueuedTask t = new QueuedTask();
            t.setId(id);
            t.setType(type);
            t.setItemRef(itemRef);
            return t;
        }
    }

    /** The in-flight do-task — proves a worker was spawned so a crash can reconcile. */
    @Data
    public static class InFlight {
        private String taskId;
        private String workerProcessId;
        private String itemId;
        /** The item's remaining chain after the do — enqueued when the reply arrives. */
        private java.util.List<String> remainingChain = new ArrayList<>();
    }

    @Data
    public static class Counters {
        private int rounds;
        private int llmCalls;
        private long tokens;
    }
}
