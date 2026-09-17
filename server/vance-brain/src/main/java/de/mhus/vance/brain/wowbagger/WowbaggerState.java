package de.mhus.vance.brain.wowbagger;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Wowbagger's persisted structure — everything needed to rotate the worker
 * threads (planning/wowbagger-engine.md §4a.3).
 *
 * <p>Stored as a serialized map under {@code engineParams.wowbaggerState}
 * and round-tripped through Jackson. The agent <b>reads</b> the structure
 * through the per-turn status block in its prompt and <b>writes</b> it
 * through the {@code wowbagger_*} tools; the pool consumes it mechanically.
 *
 * <p><b>The chunk docs are the truth</b> (Doc-Create is the commit, §6.3);
 * this structure carries the pointer, the failure ledger, the configs and
 * the counters. On re-start, chime presence reconciles the wave (adopt
 * committed, re-fire missing).
 */
@Data
public class WowbaggerState {

    // ── Run configuration (agent-written via wowbagger_configure) ──

    /** The per-record task instruction handed to every worker call. */
    private @Nullable String task;

    /** Source file path, relative to the process's WORK-target root dir. */
    private @Nullable String sourcePath;

    /** Record format: {@code lines} or {@code jsonl} — how a record is read. */
    private @Nullable String inputFormat;

    /**
     * Output-structure: how chunk results are written. {@code lines} keeps the
     * worker reply lines verbatim; {@code jsonl} requires one JSON object per
     * record. Echoed into the worker prompt and the merge target.
     */
    private @Nullable String outputFormat;

    /** Output document path for the merged result; {@code null} = default layout. */
    private @Nullable String outputDocPath;

    private int chunkSize = 50;

    /** Desired worker-thread count — the agent's knob (0 = pool idle). */
    private int threadsDesired;

    /** Every N committed records the pool wakes the agent (0 = only start/finish). */
    private int wakeEveryRecords = 1000;

    /** Per-chunk retry budget — a fresh worker call per retry. */
    private int chunkRetries = 3;

    /**
     * Source auto-backup threshold in MB: when > 0, {@code start()} copies the
     * source file into a document ({@code _wowbagger/<run>/source.*}) if its
     * size fits — pod switches then lose nothing, resume restores from the
     * document. {@code 0} = off (default — single-pod installations do not
     * need it). The agent proposes; the user decides.
     */
    private int sourceBackupMb;

    /**
     * Output-token cap per worker call; {@code null}/0 = the worker recipe's
     * {@code maxTokens} (agent-settable — the right cap depends on the chunk
     * size, which is per-run configuration; keep it far below the model's
     * context window: gateways clamp max_tokens to the context length).
     */
    private @Nullable Integer maxTokens;

    /**
     * LightLlm config profile for worker calls ({@code internal: true} recipe).
     * {@code null} = the bundled {@code wowbagger-worker} default — the agent points a
     * run at a project-local profile to choose the worker model tier.
     */
    private @Nullable String workerRecipe;

    /**
     * Last resolved worker model ({@code providerInstance:modelName}), refreshed
     * on configure/start — display-only transparency for the agent.
     */
    private @Nullable String resolvedWorkerModel;

    /**
     * The run's persistent RootDir ({@code wowbagger-<processId-prefix>} by
     * default) — source and results live here, decoupled from the process-wide
     * workTarget. Auto-assigned on first configure/start, adopted on resume.
     * {@code null} only before the first run setup.
     */
    private @Nullable String workTargetName;

    /**
     * Heartbeat wakeup while the run is grinding: an agent check-in when
     * nothing else produced news for this long ({@code 0} = off, e.g. 1800 = 30 min).
     */
    private int wakeEverySeconds;

    /**
     * Minimum seconds between failure wakeups ({@code 0} = wake on every failure).
     * The failure counter still counts every failed chunk — suppression only
     * throttles the notification, never the ledger.
     */
    private int failureCooldownSeconds = 300;

    // ── Rotation state ──

    /** Next record index to hand out (the assignment pointer). */
    private long pointer;

    /** Total record count — measured in one counting pass on first use. */
    private long recordsTotal = -1;

    /** Estimated total chunk count derived from {@link #recordsTotal}. */
    private int chunksTotal = -1;

    /** Committed records — the display counter for status/wakeups. */
    private long recordsDone;

    /**
     * Failed chunks since the agent's last acknowledgement — the counter
     * behind the (throttled) failure wakeups. The agent resets it
     * ({@code wowbagger_configure resetFailureCount} or implicitly via a re-run).
     */
    private long failureCount;

    /** Re-queued chunks (reconcile, re-run-failed) — consumed before the pointer. */
    private List<WaveChunk> retryQueue = new ArrayList<>();
    /** Chunks currently dispatched (claimed windows) — reconciled on re-start. */
    private List<WaveChunk> wave = new ArrayList<>();

    /** Chunks that exhausted their retry budget — the failure ledger. */
    private List<WaveChunk> failedChunks = new ArrayList<>();

    private Counters counters = new Counters();

    /** Whether the pool runner has completed this run (merge done, DONE). */
    private boolean finished;

    @Data
    public static class WaveChunk {

        /** 0-based chunk index — the number in the chunk doc path. */
        private int index;

        /** First record (0-based, source line) of the window. */
        private long startRecord;

        private int recordCount;

        private int attempts;

        private @Nullable String lastError;
    }

    @Data
    public static class Counters {

        private long waves;

        private long workerCalls;

        private long retries;

        private long failures;

        private long controllerTokens;

        /** Input tokens of answered worker calls — the run's cost report. */
        private long tokensIn;

        /** Output tokens of answered worker calls. */
        private long tokensOut;
    }
}
