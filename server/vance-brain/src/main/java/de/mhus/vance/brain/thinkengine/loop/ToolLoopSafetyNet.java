package de.mhus.vance.brain.thinkengine.loop;

import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The safety nets of one tool-loop turn — create one per turn, consult it
 * at the fixed points of the loop. It measures being <em>stuck</em>, never
 * volume: a long, healthy turn runs until its natural stop.
 *
 * <pre>
 *   for (int iter = 0; ; iter++) {
 *       [interrupt check — the engine's own]
 *       stop = net.beforeRound(iter, best);            // opt-in cap, wallclock
 *       reply = stream(...)  — on failure: SafetyStop.llmFailure(e, best)
 *       no tool call, blank text → SafetyStop.emptyReply(best)
 *       stop = net.onToolBatch(calls, best);           // idle-stuck
 *       dispatch tools
 *       net.afterToolBatch(calls);                     // poll throttle
 *   }
 * </pre>
 *
 * <p>Status polls of a running background job are exempt from idle-stuck
 * (waiting on a long build is progress-waiting, not a stuck loop) and are
 * throttled instead, so the loop does not burn a model call per poll in a
 * tight cycle. The wallclock and the engine's halt check still bound a
 * genuinely hung poll loop.
 */
@Slf4j
public final class ToolLoopSafetyNet {

    /** Tools that merely poll a running background job. */
    public static final Set<String> DEFAULT_POLLING_TOOLS =
            Set.of("exec_status", "client_exec_status", "work_exec_status");

    private final LoopLimits limits;
    private final ThinkProcessService thinkProcessService;
    private final String processId;
    private final Set<String> pollingTools;
    private final long deadlineMs;
    private final Deque<String> recentBatchHashes = new ArrayDeque<>();
    private int consecutivePolls;

    public ToolLoopSafetyNet(LoopLimits limits, ThinkProcessService thinkProcessService, String processId) {
        this(limits, thinkProcessService, processId, DEFAULT_POLLING_TOOLS);
    }

    /**
     * @param pollingTools tool names whose batches count as status polls —
     *     an engine whose own status tool is polled (Wowbagger's pool) adds
     *     it to {@link #DEFAULT_POLLING_TOOLS}
     */
    public ToolLoopSafetyNet(
            LoopLimits limits, ThinkProcessService thinkProcessService, String processId, Set<String> pollingTools) {
        this.limits = limits;
        this.thinkProcessService = thinkProcessService;
        this.processId = processId;
        this.pollingTools = pollingTools;
        // Per turn, not per process lifetime: resuming a process that has
        // been idle for a day must not trip the net on its first round.
        this.deadlineMs = System.currentTimeMillis() + limits.wallclockMinutes() * 60_000L;
    }

    /** Before the next model call: the opt-in round cap and the wallclock. */
    public @Nullable SafetyStop beforeRound(int iteration, String partialText) {
        if (limits.maxIterations() > 0 && iteration >= limits.maxIterations()) {
            return new SafetyStop(
                    SafetyStop.SafetyStopReason.ITERATION_LIMIT,
                    limits.maxIterations() + " rounds (maxIterations)",
                    partialText);
        }
        // ">=" so a 0-minute budget trips deterministically.
        if (System.currentTimeMillis() >= deadlineMs) {
            return new SafetyStop(
                    SafetyStop.SafetyStopReason.WALLCLOCK, limits.wallclockMinutes() + " minutes", partialText);
        }
        return null;
    }

    /** A tool batch is about to run: the idle-stuck net over consecutive batches. */
    public @Nullable SafetyStop onToolBatch(List<ToolExecutionRequest> calls, String partialText) {
        if (isPollingOnly(calls)) {
            return null;
        }
        if (isIdleStuck(recentBatchHashes, hash(calls), limits.idleStuckThreshold())) {
            return new SafetyStop(
                    SafetyStop.SafetyStopReason.IDLE_STUCK,
                    "'" + calls.get(0).name() + "' " + limits.idleStuckThreshold() + "× in a row",
                    partialText);
        }
        return null;
    }

    /** A tool batch ran: throttle the next round when it only polled. */
    public void afterToolBatch(List<ToolExecutionRequest> calls) {
        if (isPollingOnly(calls)) {
            consecutivePolls++;
            throttle();
        } else {
            consecutivePolls = 0;
        }
    }

    private boolean isPollingOnly(List<ToolExecutionRequest> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        for (ToolExecutionRequest c : calls) {
            if (!pollingTools.contains(c.name())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The wait grows with the number of consecutive status-only batches and
     * is capped; the sleep is chunked and bails on a halt request so ESC
     * latency stays around a second.
     */
    private void throttle() {
        long step = limits.pollThrottleStepMs();
        if (step <= 0) {
            return;
        }
        long max = Math.max(step, limits.pollThrottleMaxMs());
        long ms = Math.min(step * consecutivePolls, max);
        log.trace("process id='{}' poll throttle #{} — sleeping {}ms", processId, consecutivePolls, ms);
        long slept = 0;
        while (slept < ms) {
            if (thinkProcessService.isHaltRequested(processId)) {
                return; // the loop head picks up the halt
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

    /**
     * Maintains a sliding window of the last N batch hashes. True iff the
     * window is full and every entry equals the incoming batch — the model
     * called the same tools with the same arguments N times in a row. A
     * threshold of {@code 0} disables the net.
     */
    static boolean isIdleStuck(Deque<String> recentHashes, String batchHash, int threshold) {
        if (threshold <= 0) return false;
        recentHashes.addLast(batchHash);
        while (recentHashes.size() > threshold) {
            recentHashes.removeFirst();
        }
        if (recentHashes.size() < threshold) return false;
        for (String h : recentHashes) {
            if (!h.equals(batchHash)) return false;
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
}
