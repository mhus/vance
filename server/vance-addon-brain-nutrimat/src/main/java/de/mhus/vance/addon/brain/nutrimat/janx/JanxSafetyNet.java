package de.mhus.vance.addon.brain.nutrimat.janx;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * janx's safety nets — the Ford-loop nets, copied (Nutrimat links no loop
 * logic of the productive strand; drift between the two is a measurement).
 * They measure being <em>stuck</em>, never volume: idle-stuck (the same tool
 * batch N times in a row; status polls exempt and throttled), an empty
 * reply, a failed model call, the per-turn wallclock as the generous last
 * resort, and an opt-in round cap. One instance per turn.
 */
@Slf4j
final class JanxSafetyNet {

    /** Recipe param: opt-in round cap; absent / {@code 0} = no cap. */
    static final String PARAM_MAX_ITERATIONS = "maxIterations";

    /** Recipe param: per-turn wallclock net in minutes. */
    static final String PARAM_MAX_WALLCLOCK_MINUTES = "maxWallclockMinutes";

    /** Recipe param: identical batches in a row that count as stuck; {@code 0} = off. */
    static final String PARAM_IDLE_STUCK_THRESHOLD = "idleStuckThreshold";

    /** Recipe param: base wait between status-only batches in ms; {@code 0} = no throttle. */
    static final String PARAM_POLL_THROTTLE_STEP_MS = "pollThrottleStepMs";

    static final int DEFAULT_WALLCLOCK_MINUTES = 60;
    static final int DEFAULT_IDLE_STUCK_THRESHOLD = 5;
    static final int DEFAULT_POLL_THROTTLE_STEP_MS = 5000;
    static final int POLL_THROTTLE_MAX_MS = 30000;

    /** Tools that merely poll a running background job. */
    static final Set<String> POLLING_TOOLS = Set.of("exec_status", "client_exec_status", "work_exec_status");

    /** Why a net ended the turn. */
    enum Reason {
        IDLE_STUCK,
        EMPTY_REPLY,
        LLM_FAILURE,
        WALLCLOCK,
        ITERATION_LIMIT
    }

    /** A net stop: reason, technical detail, partial progress (possibly blank). */
    record Stop(Reason reason, String detail, String partialText) {

        String describe() {
            return switch (reason) {
                case IDLE_STUCK -> "it repeated the same tool call without making progress (" + detail + ")";
                case EMPTY_REPLY ->
                    "the model returned an empty response (no text, no tool call) even after automatic retries";
                case LLM_FAILURE -> "the model call failed (" + detail + ")";
                case WALLCLOCK -> "it reached the per-turn time limit (" + detail + ")";
                case ITERATION_LIMIT -> "it reached the recipe's step limit (" + detail + ")";
            };
        }

        /** For a worker that closes on the stop — the parent's only reliable signal. */
        String workerText() {
            StringBuilder sb = new StringBuilder("⚠️ TASK FAILED — this worker was stopped by a safety net: ")
                    .append(describe())
                    .append(". It is now CLOSED and cannot be resumed. The task is UNFINISHED — do not treat ")
                    .append("any text below as an answer, and do not assume the remaining steps ran. To carry ")
                    .append("the task further, start a fresh worker (tighter scope, or a different model).");
            if (!partialText.isBlank()) {
                sb.append("\n\nPartial progress:\n\n").append(partialText);
            }
            return sb.toString();
        }

        /** For a chat that parks BLOCKED — the next message continues. */
        String continuableText() {
            StringBuilder sb = new StringBuilder("⚠️ I stopped this turn because ")
                    .append(describe())
                    .append(". Reply \"continue\" to pick up from here, or give me new instructions.");
            if (!partialText.isBlank()) {
                sb.append("\n\nProgress so far:\n\n").append(partialText);
            }
            return sb.toString();
        }
    }

    private final int maxIterations;
    private final int wallclockMinutes;
    private final int idleStuckThreshold;
    private final int pollThrottleStepMs;
    private final BooleanSupplier haltRequested;
    private final long deadlineMs;
    private final Deque<String> recentBatchHashes = new ArrayDeque<>();
    private int consecutivePolls;

    /**
     * @param haltRequested consulted while throttling, so ESC is honoured
     *     within about a second of a poll wait
     */
    JanxSafetyNet(ThinkProcessDocument process, BooleanSupplier haltRequested) {
        this.maxIterations = nonNegative(process, PARAM_MAX_ITERATIONS, 0);
        this.wallclockMinutes = nonNegative(process, PARAM_MAX_WALLCLOCK_MINUTES, DEFAULT_WALLCLOCK_MINUTES);
        this.idleStuckThreshold = nonNegative(process, PARAM_IDLE_STUCK_THRESHOLD, DEFAULT_IDLE_STUCK_THRESHOLD);
        this.pollThrottleStepMs = nonNegative(process, PARAM_POLL_THROTTLE_STEP_MS, DEFAULT_POLL_THROTTLE_STEP_MS);
        this.haltRequested = haltRequested;
        // Per turn, not per process lifetime.
        this.deadlineMs = System.currentTimeMillis() + wallclockMinutes * 60_000L;
    }

    /** Before the next model call: the opt-in round cap and the wallclock. */
    @Nullable
    Stop beforeRound(int iteration, String partialText) {
        if (maxIterations > 0 && iteration >= maxIterations) {
            return new Stop(Reason.ITERATION_LIMIT, maxIterations + " rounds (maxIterations)", partialText);
        }
        // ">=" so a 0-minute budget trips deterministically.
        if (System.currentTimeMillis() >= deadlineMs) {
            return new Stop(Reason.WALLCLOCK, wallclockMinutes + " minutes", partialText);
        }
        return null;
    }

    /** A tool batch is about to run: idle-stuck over consecutive batches. */
    @Nullable
    Stop onToolBatch(List<ToolExecutionRequest> calls, String partialText) {
        if (isPollingOnly(calls) || idleStuckThreshold <= 0) {
            return null;
        }
        recentBatchHashes.addLast(hash(calls));
        while (recentBatchHashes.size() > idleStuckThreshold) {
            recentBatchHashes.removeFirst();
        }
        if (recentBatchHashes.size() < idleStuckThreshold) {
            return null;
        }
        String first = recentBatchHashes.peekFirst();
        for (String h : recentBatchHashes) {
            if (!h.equals(first)) return null;
        }
        return new Stop(
                Reason.IDLE_STUCK, "'" + calls.get(0).name() + "' " + idleStuckThreshold + "× in a row", partialText);
    }

    /** A tool batch ran: throttle the next round when it only polled. */
    void afterToolBatch(List<ToolExecutionRequest> calls) {
        if (!isPollingOnly(calls)) {
            consecutivePolls = 0;
            return;
        }
        consecutivePolls++;
        if (pollThrottleStepMs <= 0) {
            return;
        }
        long ms = Math.min((long) pollThrottleStepMs * consecutivePolls, POLL_THROTTLE_MAX_MS);
        long slept = 0;
        while (slept < ms) {
            if (haltRequested.getAsBoolean()) {
                return; // the next round() picks up the halt
            }
            long chunk = Math.min(1000, ms - slept);
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            slept += chunk;
        }
    }

    private static boolean isPollingOnly(List<ToolExecutionRequest> calls) {
        if (calls.isEmpty()) return false;
        for (ToolExecutionRequest c : calls) {
            if (!POLLING_TOOLS.contains(c.name())) return false;
        }
        return true;
    }

    private static String hash(List<ToolExecutionRequest> calls) {
        StringBuilder sb = new StringBuilder();
        for (ToolExecutionRequest c : calls) {
            sb.append(c.name())
                    .append('(')
                    .append(c.arguments() == null ? "" : c.arguments())
                    .append(")|");
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * Reads a non-negative integer param — runtime overlay
     * ({@code //nutrimat set}) over the recipe params. A negative or
     * non-numeric value is a typo: it falls back with a warning instead of
     * disabling (or tripping) a net.
     */
    private static int nonNegative(ThinkProcessDocument process, String key, int fallback) {
        Object raw = null;
        Map<String, Object> overrides = process.getEngineParamOverrides();
        if (overrides != null && overrides.containsKey(key)) {
            raw = overrides.get(key);
        } else if (process.getEngineParams() != null) {
            raw = process.getEngineParams().get(key);
        }
        if (raw == null) return fallback;
        Integer parsed = null;
        if (raw instanceof Number n) {
            parsed = n.intValue();
        } else if (raw instanceof String s) {
            try {
                parsed = Integer.valueOf(s.strip());
            } catch (NumberFormatException ignored) {
                // handled below
            }
        }
        if (parsed == null || parsed < 0) {
            log.warn("Nutrimat[janx] id='{}' ignoring {}='{}' — not a non-negative number", process.getId(), key, raw);
            return fallback;
        }
        return parsed;
    }
}
