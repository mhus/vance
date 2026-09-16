package de.mhus.vance.brain.command;

import de.mhus.vance.brain.wowbagger.WowbaggerEngine;
import de.mhus.vance.brain.wowbagger.WowbaggerPoolService;
import de.mhus.vance.brain.wowbagger.WowbaggerState;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Read-only Wowbagger diagnostics — the {@code //wowbagger} engine-command
 * verb: everything about a run without waking the agent.
 *
 * <p>Surface:
 * <ul>
 *   <li>{@code //wowbagger} / {@code //wowbagger info} — the full picture:
 *       run state (idle/running/finished, threads), task and source, progress
 *       (pointer/records/percent), worker model + approval, wakeup intervals,
 *       failure ledger with per-chunk reasons, and the result doc path.</li>
 * </ul>
 *
 * <p>{@link #runsOnLane()} is {@code false} (Zaphod argument): a diagnostic
 * verb that queues behind an agent turn is useless exactly when the turn
 * hangs — which is when the user needs to see whether the pool is still
 * grinding. The pool's reads are {@code handle.lock}-synchronized and every
 * chunk transition persists atomically, so a bypassed read always sees a
 * consistent last step.
 *
 * <p>State comes from the pool (live handle while running, persisted
 * structure otherwise) — the same source the agent's status block renders.
 * The handler never mutates: pool reads are pure queries.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WowbaggerCommandHandler implements EngineCommandHandler {

    private static final String SUB_INFO = "info";
    private static final int TASK_PREVIEW_CHARS = 160;
    private static final int ERROR_PREVIEW_CHARS = 200;
    private static final int FAILED_CHUNK_LIMIT = 5;

    private final WowbaggerPoolService pool;

    @Override
    public String verb() {
        return WowbaggerEngine.NAME;
    }

    @Override
    public boolean runsOnLane() {
        return false;
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        if (!WowbaggerEngine.NAME.equals(process.getThinkEngine())) {
            return EngineCommandResult.error("Process '" + process.getName() + "' runs engine '"
                    + process.getThinkEngine() + "' — the '" + WowbaggerEngine.NAME
                    + "' verb needs a Wowbagger process.");
        }
        String[] tokens = splitFirstToken(argText(command));
        String sub = tokens[0].isEmpty() ? SUB_INFO : tokens[0].toLowerCase(Locale.ROOT);
        if (!SUB_INFO.equals(sub)) {
            return EngineCommandResult.error("Unknown subcommand '" + sub + "' — known: " + SUB_INFO);
        }
        WowbaggerState state = pool.structure(process.getId());
        boolean running = pool.isRunning(process.getId());
        return EngineCommandResult.ok(renderInfo(process, state, running), infoValue(process, state, running));
    }

    // ──────────────────── info ────────────────────

    private String renderInfo(ThinkProcessDocument process, WowbaggerState s, boolean running) {
        StringBuilder msg = new StringBuilder("Wowbagger run — ");
        msg.append(s.isFinished() ? "finished" : running ? "RUNNING" : "idle");
        if (running) {
            msg.append(" (").append(s.getThreadsDesired()).append(" thread(s) desired)");
        }
        if (s.getTask() != null && !s.getTask().isBlank()) {
            msg.append("\nTask: ").append(truncate(s.getTask(), TASK_PREVIEW_CHARS));
        } else {
            msg.append("\nTask: (not set — the agent has not configured the run yet)");
        }
        if (s.getSourcePath() != null) {
            msg.append("\nSource: ")
                    .append(s.getSourcePath())
                    .append(" (")
                    .append(s.getInputFormat() == null ? "?" : s.getInputFormat())
                    .append(" → ")
                    .append(s.getOutputFormat() == null ? "?" : s.getOutputFormat())
                    .append(", chunk ")
                    .append(s.getChunkSize())
                    .append(')');
        }
        if (s.getRecordsTotal() >= 0) {
            msg.append("\nProgress: ")
                    .append(s.getRecordsDone())
                    .append('/')
                    .append(s.getRecordsTotal())
                    .append(" records (")
                    .append(percent(s))
                    .append("), pointer at ")
                    .append(s.getPointer());
        }
        msg.append("\nWorker: recipe ")
                .append(s.getWorkerRecipe() == null ? "wowbagger-worker" : s.getWorkerRecipe())
                .append(" → ")
                .append(s.getResolvedWorkerModel() == null ? "(not resolved yet)" : s.getResolvedWorkerModel())
                .append(" (approved: ")
                .append(pool.isWorkerModelApproved(process, s))
                .append(')');
        msg.append("\nWakeups: every ")
                .append(Math.max(0, s.getWakeEveryRecords()))
                .append(" records, heartbeat ")
                .append(Math.max(0, s.getWakeEverySeconds()))
                .append("s, failure cooldown ")
                .append(Math.max(0, s.getFailureCooldownSeconds()))
                .append("s");
        msg.append("\nFailures: ")
                .append(s.getFailedChunks().size())
                .append(" failed chunk(s), ")
                .append(s.getFailureCount())
                .append(" unacked failure(s)");
        int limit = Math.min(s.getFailedChunks().size(), FAILED_CHUNK_LIMIT);
        for (int i = 0; i < limit; i++) {
            WowbaggerState.WaveChunk fc = s.getFailedChunks().get(i);
            msg.append("\n - #")
                    .append(fc.getIndex())
                    .append(" (records ")
                    .append(fc.getStartRecord())
                    .append("–")
                    .append(fc.getStartRecord() + Math.max(0, fc.getRecordCount()) - 1)
                    .append(", ")
                    .append(fc.getAttempts())
                    .append(" attempts)");
            if (fc.getLastError() != null) {
                msg.append(": ").append(truncate(fc.getLastError(), ERROR_PREVIEW_CHARS));
            }
        }
        if (s.getFailedChunks().size() > limit) {
            msg.append("\n … and ").append(s.getFailedChunks().size() - limit).append(" more");
        }
        if (!s.getRetryQueue().isEmpty()) {
            msg.append("\nRetry queue: ").append(s.getRetryQueue().size()).append(" chunk(s) pending");
        }
        if (s.getOutputDocPath() != null) {
            msg.append("\nResult: ").append(s.getOutputDocPath());
        }
        return msg.toString();
    }

    private Map<String, Object> infoValue(ThinkProcessDocument process, WowbaggerState s, boolean running) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("running", running);
        value.put("finished", s.isFinished());
        value.put("threadsDesired", s.getThreadsDesired());
        value.put("task", s.getTask());
        value.put("source", s.getSourcePath());
        value.put("inputFormat", s.getInputFormat());
        value.put("outputFormat", s.getOutputFormat());
        value.put("chunkSize", s.getChunkSize());
        value.put("pointer", s.getPointer());
        value.put("recordsDone", s.getRecordsDone());
        value.put("recordsTotal", s.getRecordsTotal());
        value.put("chunksTotal", s.getChunksTotal());
        value.put("workerRecipe", s.getWorkerRecipe() == null ? "wowbagger-worker" : s.getWorkerRecipe());
        value.put("resolvedWorkerModel", s.getResolvedWorkerModel());
        value.put("modelApproved", pool.isWorkerModelApproved(process, s));
        value.put("wakeEveryRecords", s.getWakeEveryRecords());
        value.put("wakeEverySeconds", s.getWakeEverySeconds());
        value.put("failureCooldownSeconds", s.getFailureCooldownSeconds());
        value.put("failureCount", s.getFailureCount());
        List<Map<String, Object>> failed = new ArrayList<>();
        for (WowbaggerState.WaveChunk fc : s.getFailedChunks()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", fc.getIndex());
            entry.put("startRecord", fc.getStartRecord());
            entry.put("recordCount", fc.getRecordCount());
            entry.put("attempts", fc.getAttempts());
            entry.put("lastError", fc.getLastError());
            failed.add(entry);
        }
        value.put("failedChunks", failed);
        value.put("retryQueueSize", s.getRetryQueue().size());
        value.put("outputDoc", s.getOutputDocPath());
        return value;
    }

    // ──────────────────── helpers ────────────────────

    private static String percent(WowbaggerState s) {
        if (s.getRecordsTotal() <= 0) {
            return "?%";
        }
        return Math.round(s.getRecordsDone() * 100.0 / s.getRecordsTotal()) + "%";
    }

    private static String truncate(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String argText(EngineCommand command) {
        Object text = command.args().get("text");
        return text == null ? "" : text.toString().trim();
    }

    private static String[] splitFirstToken(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            return new String[] {"", ""};
        }
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) {
                return new String[] {t.substring(0, i), t.substring(i + 1).trim()};
            }
        }
        return new String[] {t, ""};
    }
}
