package de.mhus.vance.brain.delegate;

import de.mhus.vance.brain.slartibartfast.SlartibartfastEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code process_create_delegate} fallback path: spawn
 * Slartibartfast for a task description, wait synchronously for it
 * to terminate, return the persisted recipe path on success.
 *
 * <p>Used when the recipe selector returns {@code NONE} — no
 * existing project recipe matches. Slartibartfast generates a fresh
 * recipe (Marvin or Vogon shape, depending on
 * {@link #DEFAULT_OUTPUT_SCHEMA}) tailored to the task; the calling
 * tool then spawns that recipe just like a {@code MATCH}.
 *
 * <p>Synchronous wait with a hard timeout — the calling LLM tool
 * round-trip is blocked while Slart runs (~60-180s typical). For
 * production callers that can't tolerate the wait, the calling
 * tool exposes {@code fallbackOnNone=false} to skip this entirely.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlartibartfastFallback {

    /** Schema-type Slart emits when invoked from delegate. Marvin
     *  recipe gives the most flexibility — covers linear and
     *  task-tree shapes. We could expose this as a tool-param
     *  later if a caller needs Vogon specifically. */
    public static final String DEFAULT_OUTPUT_SCHEMA = "marvin-recipe";

    /** Hard ceiling on the synchronous wait. Slart with no
     *  recoveries lands in 60-90s; with up to 5 recoveries it can
     *  reach ~3min. Past that we assume it's stuck — better to
     *  surface a timeout than block the caller forever. */
    static final Duration WAIT_TIMEOUT = Duration.ofMinutes(4);

    /** Polling cadence — Slart runs async, we poll its status doc.
     *  500ms keeps the wait responsive without hammering Mongo. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;

    /**
     * Drives the full fallback round-trip. Returns the result
     * synchronously after Slart terminates (or times out). Caller
     * uses the returned record to either spawn the generated
     * recipe (on {@link Outcome#GENERATED}) or surface the
     * diagnostic to the user (everything else).
     */
    public Result invoke(
            ThinkProcessDocument caller,
            String taskDescription,
            String spawnedProcessName) {

        ThinkProcessDocument slart = spawnSlart(caller, taskDescription, spawnedProcessName);
        log.info("SlartibartfastFallback: spawned slart id='{}' for task '{}' "
                        + "(caller='{}', timeout={})",
                slart.getId(), abbrev(taskDescription, 60),
                caller.getId(), WAIT_TIMEOUT);

        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ThinkProcessDocument> fresh = thinkProcessService.findById(slart.getId());
            if (fresh.isEmpty()) {
                return Result.failed(slart.getId(),
                        "Slart process disappeared from think_processes");
            }
            String status = fresh.get().getStatus() == null
                    ? "" : fresh.get().getStatus().name();
            if ("CLOSED".equals(status) || "STALE".equals(status)
                    || "FAILED".equals(status)) {
                return inspect(fresh.get());
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Result.failed(slart.getId(),
                        "interrupted while waiting for Slart");
            }
        }
        return Result.timedOut(slart.getId(), WAIT_TIMEOUT);
    }

    // ──────────────────── internals ────────────────────

    private ThinkProcessDocument spawnSlart(
            ThinkProcessDocument caller,
            String taskDescription,
            String spawnedProcessName) {
        Map<String, Object> engineParams = new LinkedHashMap<>();
        engineParams.put(SlartibartfastEngine.USER_DESCRIPTION_KEY, taskDescription);
        engineParams.put(SlartibartfastEngine.OUTPUT_SCHEMA_TYPE_KEY, DEFAULT_OUTPUT_SCHEMA);

        ThinkProcessDocument fresh = thinkProcessService.create(
                caller.getTenantId(),
                caller.getProjectId(),
                caller.getSessionId(),
                /*name*/ "delegate-fallback-slart-" + spawnedProcessName,
                /*engine*/ SlartibartfastEngine.NAME,
                /*engineVersion*/ SlartibartfastEngine.VERSION,
                /*title*/ "process_create_delegate fallback (Slart)",
                /*goal*/ taskDescription,
                /*parentProcessId*/ caller.getId(),
                engineParams,
                /*recipeName*/ null,
                /*promptOverride*/ null,
                /*promptOverrideSmall*/ null,
                /*promptMode*/ null,
                /*dataRelayCorrectionOverride*/ null,
                /*allowedToolsOverride*/ null,
                caller.getConnectionProfile(),
                /*defaultActiveSkills*/ List.of(),
                /*allowedSkillsOverride*/ null);
        thinkEngineService.start(fresh);
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private Result inspect(ThinkProcessDocument terminal) {
        Map<String, Object> ep = terminal.getEngineParams();
        if (ep == null) {
            return Result.failed(terminal.getId(),
                    "Slart terminated with no engineParams");
        }
        Object stateRaw = ep.get(SlartibartfastEngine.STATE_KEY);
        if (!(stateRaw instanceof Map<?, ?>)) {
            return Result.failed(terminal.getId(),
                    "Slart engineParams missing architectState");
        }
        Map<String, Object> state = (Map<String, Object>) stateRaw;
        String slartStatus = String.valueOf(state.get("status"));
        if (!"DONE".equals(slartStatus)) {
            String reason = state.get("failureReason") instanceof String s && !s.isBlank()
                    ? s
                    : "Slart status=" + slartStatus;
            return Result.failed(terminal.getId(),
                    "Slart did not produce a recipe: " + reason);
        }
        Object pathRaw = state.get("persistedRecipePath");
        if (!(pathRaw instanceof String path) || path.isBlank()) {
            return Result.failed(terminal.getId(),
                    "Slart DONE but persistedRecipePath missing");
        }
        // Strip the "recipes/" prefix and ".yaml" suffix to recover
        // the recipe name as RecipeResolver expects it.
        String recipeName = stripRecipeName(path);
        return Result.generated(terminal.getId(), recipeName, path);
    }

    private static String stripRecipeName(String path) {
        String n = path;
        if (n.startsWith("recipes/")) n = n.substring("recipes/".length());
        if (n.endsWith(".yaml")) n = n.substring(0, n.length() - ".yaml".length());
        return n;
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ──────────────────── result types ────────────────────

    public enum Outcome { GENERATED, FAILED, TIMED_OUT }

    /**
     * Outcome of a fallback round-trip. {@link #recipeName} is set
     * only on {@link Outcome#GENERATED}; for failures the
     * {@link #rationale} carries a one-line diagnostic suitable
     * for the caller's response.
     */
    public record Result(
            Outcome outcome,
            String slartProcessId,
            @Nullable String recipeName,
            @Nullable String recipePath,
            String rationale) {

        public static Result generated(String pid, String recipeName, String path) {
            return new Result(Outcome.GENERATED, pid, recipeName, path,
                    "Slart generated recipe '" + recipeName + "'");
        }

        public static Result failed(String pid, String why) {
            return new Result(Outcome.FAILED, pid, null, null, why);
        }

        public static Result timedOut(String pid, Duration timeout) {
            return new Result(Outcome.TIMED_OUT, pid, null, null,
                    "Slart did not terminate within " + timeout);
        }
    }
}
