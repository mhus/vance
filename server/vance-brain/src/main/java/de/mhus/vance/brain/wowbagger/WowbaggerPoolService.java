package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentService.DocumentAlreadyExistsException;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The mechanical side of Wowbagger (§4a.2): a per-process worker-thread pool
 * that rotates chunks off the source file.
 *
 * <p><b>Always present, possibly zero threads.</b> An agent turn starts or
 * stops the pool; every change of {@code threadsDesired} (agent tool, run
 * finish) is applied by the runner at its next tick — surplus threads drain
 * their current chunk, then die; missing ones spawn on demand.
 *
 * <p><b>Concurrency model.</b> The {@link Handle} owns the ONE live
 * {@link WowbaggerState} instance; every mutation (claim, commit, failure,
 * counter) happens under the handle lock, and {@code persist} writes that
 * instance to {@code engineParams} — the DB is persistence, never a second
 * authority while running. Tools and the prompt read the handle's
 * last-persisted snapshot. The chunk docs remain the commit truth (§6.3):
 * on (re-)start the persisted wave reconciles against their presence.
 *
 * <p><bWakeups</b> to the agent: every {@code wakeEveryRecords} committed
 * records, on failure, at run finish and at pool start — a chat-history note
 * plus a pending message (auto-wake of the agent's lane). The wakeup text is
 * the "chunk fertig → Chat-History" vermerk, throttled by the interval.
 */
@Service
@Slf4j
public class WowbaggerPoolService {

    public static final String METRIC_CHUNKS = "vance.wowbagger.chunks";
    public static final String METRIC_RECORDS = "vance.wowbagger.records";
    public static final String METRIC_LLM_CALLS = "vance.wowbagger.llm.calls";
    public static final String METRIC_RETRIES = "vance.wowbagger.retries";

    public static final String ENGINE_STATE_KEY = "wowbaggerState";

    static final String FORMAT_LINES = "lines";
    static final String FORMAT_JSONL = "jsonl";
    static final String RUN_FOLDER_PREFIX = "_wowbagger";

    private static final String OUTCOME_COMMITTED = "committed";
    /** Bundled LightLlm profile used when the structure sets no workerRecipe. */
    private static final String DEFAULT_WORKER_RECIPE = "wowbagger-worker";
    /**
     * Comma-separated worker-model allowlist (patterns with {@code *} wildcards),
     * matched against the resolved {@code providerInstance:modelName}. Read at
     * PROJECT then tenant-{@code _tenant} scope — deliberately NOT at think-process
     * scope, so the agent cannot approve a model for itself via a process setting.
     */
    public static final String ALLOWED_MODELS_KEY = "wowbagger.allowed-models";
    /** Prefix of the run's auto-assigned persistent RootDir (§ run root). */
    private static final String RUN_DIR_PREFIX = "wowbagger-";

    private static final int RUN_DIR_ID_CHARS = 8;

    private static final String OUTCOME_FAILED = "failed";
    private static final long TICK_MS = 500;

    /** One chunk's claimed window with its records (transient, worker-local). */
    record Claim(WowbaggerState.WaveChunk chunk, List<String> records) {}

    public record RunView(
            WowbaggerState structure,
            boolean running,
            int threadsActive,
            @Nullable String runError) {}

    /** Per-process run handle — the live state, the lock, the thread list. */
    private static final class Handle {

        final String processId;
        final Object lock = new Object();
        /** The live state instance while running; mutated only under {@link #lock}. */
        @Nullable
        ThinkProcessDocument process;

        WowbaggerState live = new WowbaggerState();
        volatile boolean running;
        volatile boolean stopRequested;
        volatile int threadsActive;
        volatile @Nullable String runError;
        /** Last agent wakeup of any kind (progress/failure/heartbeat) — the heartbeat base. */
        volatile long lastWakeupAtMs;
        /** Last failure wakeup — the failure-cooldown base. */
        volatile long lastFailureWakeupAtMs;

        final AtomicLong sequence = new AtomicLong();
        final List<Thread> workers = new ArrayList<>();

        Handle(String processId) {
            this.processId = processId;
        }
    }

    private final Map<String, Handle> runs = new ConcurrentHashMap<>();

    private final ThinkProcessService thinkProcessService;
    private final LightLlmService lightLlmService;
    private final DocumentService documentService;
    private final WorkspaceService workspaceService;
    private final WorkTargetService workTargetService;
    private final MetricService metricService;
    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;
    private final de.mhus.vance.brain.recipe.RecipeResolver recipeResolver;
    private final de.mhus.vance.brain.ai.AiModelResolver aiModelResolver;
    private final de.mhus.vance.shared.settings.SettingService settingService;

    public WowbaggerPoolService(
            ThinkProcessService thinkProcessService,
            LightLlmService lightLlmService,
            DocumentService documentService,
            WorkspaceService workspaceService,
            WorkTargetService workTargetService,
            MetricService metricService,
            ObjectMapper objectMapper,
            ChatMessageService chatMessageService,
            de.mhus.vance.brain.recipe.RecipeResolver recipeResolver,
            de.mhus.vance.brain.ai.AiModelResolver aiModelResolver,
            de.mhus.vance.shared.settings.SettingService settingService) {
        this.thinkProcessService = thinkProcessService;
        this.lightLlmService = lightLlmService;
        this.documentService = documentService;
        this.workspaceService = workspaceService;
        this.workTargetService = workTargetService;
        this.metricService = metricService;
        this.objectMapper = objectMapper;
        this.chatMessageService = chatMessageService;
        this.recipeResolver = recipeResolver;
        this.aiModelResolver = aiModelResolver;
        this.settingService = settingService;
    }

    // ──────────────────── Lifecycle ────────────────────

    /**
     * Starts (or resumes) the pool: loads the persisted structure into a live
     * handle, requeues the un-committed wave (reconcile §6.3), measures the
     * source once and spawns the runner. Sends the start wakeup.
     */
    public RunView start(ThinkProcessDocument process) throws IOException {
        Handle handle = handle(process.getId());
        synchronized (handle.lock) {
            if (handle.running) {
                return view(handle);
            }
            WowbaggerState state = loadState(process);
            ensureWorkRoot(process, state);
            reconcileWave(process, state);
            if (state.getRecordsTotal() < 0 && !isBlank(state.getSourcePath())) {
                Path source = resolveSource(process, state);
                state.setRecordsTotal(countLines(source));
                state.setChunksTotal(
                        (int) ((state.getRecordsTotal() + state.getChunkSize() - 1) / state.getChunkSize()));
            }
            if (isBlank(state.getOutputDocPath())) {
                state.setOutputDocPath(defaultResultPath(process));
            }
            state.setFinished(false);
            handle.live = state;
            handle.stopRequested = false;
            handle.runError = null;
            // Cost gate (§ Model approval): the worker model must be on the
            // operator's allowlist before a single record is processed — and
            // before `running` flips true, so a refusal leaves the pool idle.
            // The resolution is persisted even on refusal so the agent (and
            // the user) see WHAT was refused.
            try {
                checkModelApproval(process, state);
            } catch (RuntimeException e) {
                persist(process, state);
                throw e;
            }
            handle.running = true;
            persist(process, state);
        }
        Thread runner = new Thread(() -> runLoop(process), "wowbagger-runner-" + process.getId());
        runner.setDaemon(true);
        runner.start();
        wakeup(process, "pool started — " + describe(handle));
        return view(handle);
    }

    /** Stops the pool; threads drain their current chunk, then die. */
    public RunView stop(String processId) {
        Handle handle = runs.get(processId);
        if (handle == null) {
            return new RunView(load(processId), false, 0, null);
        }
        synchronized (handle.lock) {
            handle.stopRequested = true;
            handle.running = false;
            for (Thread w : handle.workers) {
                w.interrupt();
            }
        }
        return view(handle);
    }

    /**
     * Re-queues the failed chunks (attempts reset) — the retryFailed knob the
     * agent drives. Idempotent: a call with an empty failure ledger is a no-op
     * returning the current view.
     */
    public RunView reRunFailed(ThinkProcessDocument process) {
        Handle handle = handle(process.getId());
        synchronized (handle.lock) {
            WowbaggerState state = handle.running ? handle.live : loadState(process);
            for (WowbaggerState.WaveChunk failed : state.getFailedChunks()) {
                failed.setAttempts(0);
                failed.setLastError(null);
                state.getRetryQueue().add(failed);
            }
            int requeued = state.getRetryQueue().size();
            state.setFailedChunks(new ArrayList<>());
            state.setFailureCount(0); // the agent acknowledged the failures
            state.setFinished(false);
            // The same cost gate as start: a re-run must not become a bypass.
            try {
                checkModelApproval(process, state);
            } catch (RuntimeException e) {
                persist(process, state);
                throw e;
            }
            persist(process, state);
            if (!handle.running) {
                handle.live = state;
                handle.running = true;
                handle.stopRequested = false;
                handle.runError = null;
                Thread runner = new Thread(() -> runLoop(process), "wowbagger-runner-" + process.getId());
                runner.setDaemon(true);
                runner.start();
            }
            log.info("Wowbagger pool id='{}' re-run failed: {} chunk(s) requeued", process.getId(), requeued);
            return view(handle);
        }
    }

    /** Whether a pool runner currently spins for the process. */
    public boolean isRunning(String processId) {
        Handle handle = runs.get(processId);
        return handle != null && handle.running;
    }

    /** Latest known structure — the handle snapshot when running, else from the DB. */
    public WowbaggerState structure(String processId) {
        Handle handle = runs.get(processId);
        if (handle != null) {
            synchronized (handle.lock) {
                return handle.live;
            }
        }
        return load(processId);
    }

    RunView view(Handle handle) {
        WowbaggerState s;
        synchronized (handle.lock) {
            s = handle.live;
        }
        int active;
        synchronized (handle.lock) {
            active = handle.threadsActive;
        }
        return new RunView(s, handle.running, active, handle.runError);
    }

    private WowbaggerState load(String processId) {
        return thinkProcessService.findById(processId).map(this::loadState).orElseGet(WowbaggerState::new);
    }

    private Handle handle(String processId) {
        return runs.computeIfAbsent(processId, Handle::new);
    }

    // ──────────────────── The runner ────────────────────

    /**
     * One runner thread per start: ticks, maintains the desired worker count,
     * stops on the stop flag, a framework CLOSE, or run completion (merge +
     * finish wakeup). The runner never touches the process lifecycle — the
     * pool reports, the agent decides.
     */
    private void runLoop(ThinkProcessDocument process) {
        Handle handle = handle(process.getId());
        try {
            while (true) {
                if (handle.stopRequested) {
                    log.info("Wowbagger pool id='{}' stopped by the agent", process.getId());
                    return;
                }
                if (liveStatus(process.getId()) == ThinkProcessStatus.CLOSED) {
                    log.info("Wowbagger pool id='{}' process closed — pool exits", process.getId());
                    synchronized (handle.lock) {
                        handle.running = false;
                    }
                    return;
                }
                WowbaggerState state;
                synchronized (handle.lock) {
                    state = handle.live;
                }
                boolean workRemaining;
                synchronized (handle.lock) {
                    workRemaining =
                            !pointerAtEnd(state) || !state.getRetryQueue().isEmpty();
                }
                syncThreads(process, handle, state, workRemaining);
                boolean drained;
                synchronized (handle.lock) {
                    drained = pointerAtEnd(state)
                            && state.getRetryQueue().isEmpty()
                            && activeWorkers(handle) == 0
                            && state.getRecordsTotal() >= 0;
                }
                if (drained) {
                    finish(process, handle, state);
                    return;
                }
                maybeHeartbeat(process, handle, state);
                Thread.sleep(TICK_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Wowbagger pool id='{}' runner crashed: {}", process.getId(), e.toString(), e);
            synchronized (handle.lock) {
                handle.running = false;
                handle.runError = e.toString();
            }
            wakeup(process, "pool crashed: " + e.getMessage());
        }
    }

    private static boolean pointerAtEnd(WowbaggerState state) {
        return state.getPointer() >= state.getRecordsTotal();
    }

    /** Applies {@code threadsDesired}: spawns missing workers, interrupts surplus ones.
     * Spawns only while work remains — a drained pool must not respawn zombies
     * (they would keep the runner's drained check forever at active > 0). */
    private void syncThreads(ThinkProcessDocument process, Handle handle, WowbaggerState state, boolean workRemaining) {
        synchronized (handle.lock) {
            handle.workers.removeIf(w -> !w.isAlive());
            handle.threadsActive = handle.workers.size();
            int desired = workRemaining ? Math.max(0, state.getThreadsDesired()) : 0;
            while (handle.workers.size() < desired) {
                Thread worker =
                        new Thread(() -> workerLoop(process), "wowbagger-worker-" + handle.sequence.incrementAndGet());
                worker.setDaemon(true);
                handle.workers.add(worker);
                worker.start();
            }
            while (handle.workers.size() > desired) {
                Thread surplus = handle.workers.remove(handle.workers.size() - 1);
                surplus.interrupt();
            }
        }
    }

    private int activeWorkers(Handle handle) {
        handle.workers.removeIf(w -> !w.isAlive());
        return handle.workers.size();
    }

    /**
     * One worker thread: rotates chunks until the stop flag or the interrupt
     * (resize/suspend) arrives or the source is drained — the thread then dies
     * by itself; the runner respawns it while desired > 0 and records remain.
     */
    private void workerLoop(ThinkProcessDocument process) {
        Handle handle = handle(process.getId());
        try {
            while (!handle.stopRequested && !Thread.currentThread().isInterrupted()) {
                Claim claim;
                synchronized (handle.lock) {
                    claim = claimNext(process, handle.live);
                }
                if (claim == null) {
                    return; // drained for now — the runner respawns when more work exists
                }
                processClaim(process, claim);
                synchronized (handle.lock) {
                    persist(process, handle.live);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Wowbagger worker id='{}' crashed: {}", process.getId(), e.toString(), e);
            boolean wake;
            synchronized (handle.lock) {
                WowbaggerState.WaveChunk marker = new WowbaggerState.WaveChunk();
                marker.setIndex((int) (handle.live.getPointer() / Math.max(1, handle.live.getChunkSize())));
                marker.setStartRecord(handle.live.getPointer());
                marker.setRecordCount(0);
                marker.setAttempts(handle.live.getChunkRetries());
                marker.setLastError(truncate(e.toString(), 300));
                handle.live.getFailedChunks().add(marker);
                handle.live.setFailureCount(handle.live.getFailureCount() + 1);
                persist(process, handle.live);
                wake = failureWakeupDue(handle);
            }
            if (wake) {
                wakeup(
                        process,
                        "worker crashed: " + e.getMessage() + " — " + handle.live.getFailureCount()
                                + " failure(s) since your last ack");
            }
        }
    }

    // ──────────────────── Claim / process / publish ────────────────────

    /**
     * Claims the next window: first from the retry queue (re-fires), then at
     * the pointer. The claim reads the records, registers the chunk in the
     * wave and advances the pointer — atomically under the caller's lock.
     * Returns {@code null} when nothing is claimable (source drained).
     */
    private @Nullable Claim claimNext(ThinkProcessDocument process, WowbaggerState state) {
        if (!state.getRetryQueue().isEmpty()) {
            WowbaggerState.WaveChunk requeued = state.getRetryQueue().removeFirst();
            List<String> records = readWindow(process, state, requeued.getStartRecord(), requeued.getRecordCount());
            if (records.isEmpty()) {
                return null; // source shrank — park as failed, agent decides
            }
            state.getWave().add(requeued);
            return new Claim(requeued, records);
        }
        if (state.getRecordsTotal() < 0 || pointerAtEnd(state)) {
            return null;
        }
        List<String> records = readWindow(process, state, state.getPointer(), state.getChunkSize());
        if (records.isEmpty()) {
            state.setRecordsTotal(state.getPointer()); // EOF is authoritative
            return null;
        }
        WowbaggerState.WaveChunk chunk = new WowbaggerState.WaveChunk();
        chunk.setIndex((int) (state.getPointer() / state.getChunkSize()));
        chunk.setStartRecord(state.getPointer());
        chunk.setRecordCount(records.size());
        state.getWave().add(chunk);
        state.setPointer(state.getPointer() + records.size());
        return new Claim(chunk, records);
    }

    /** Reads a window with the pool's own source resolution; EOF-authoritative. */
    private List<String> readWindow(ThinkProcessDocument process, WowbaggerState state, long startRecord, int count) {
        try {
            return readWindowAt(resolveSource(process, state), startRecord, count);
        } catch (IOException e) {
            throw new IllegalStateException("source lost: " + e.getMessage(), e);
        }
    }

    private void processClaim(ThinkProcessDocument process, Claim claim) {
        Handle handle = handle(process.getId());
        WowbaggerState.WaveChunk chunk = claim.chunk();
        boolean failed = false;
        while (true) {
            try {
                List<String> reply = callWorker(process, handle.live, claim.records());
                publishChunk(process, handle.live, chunk, reply);
                synchronized (handle.lock) {
                    handle.live.getWave().remove(chunk);
                    handle.live.setRecordsDone(handle.live.getRecordsDone() + chunk.getRecordCount());
                    maybeWakeOnProgress(process, handle.live);
                    metricService
                            .counter(METRIC_CHUNKS, "outcome", OUTCOME_COMMITTED)
                            .increment();
                }
                return;
            } catch (RuntimeException e) {
                synchronized (handle.lock) {
                    chunk.setAttempts(chunk.getAttempts() + 1);
                    chunk.setLastError(truncate(e.toString(), 300));
                    if (chunk.getAttempts() > handle.live.getChunkRetries()) {
                        failed = true;
                    } else {
                        handle.live
                                .getCounters()
                                .setRetries(handle.live.getCounters().getRetries() + 1);
                    }
                    metricService.counter(METRIC_RETRIES).increment();
                }
                if (failed) {
                    break;
                }
            }
        }
        boolean wake;
        synchronized (handle.lock) {
            handle.live.getWave().remove(chunk);
            handle.live.getFailedChunks().add(chunk);
            handle.live.setFailureCount(handle.live.getFailureCount() + 1);
            handle.live.getCounters().setFailures(handle.live.getCounters().getFailures() + 1);
            persist(process, handle.live);
            wake = failureWakeupDue(handle);
        }
        metricService.counter(METRIC_CHUNKS, "outcome", OUTCOME_FAILED).increment();
        if (wake) {
            wakeup(
                    process,
                    "chunk #" + chunk.getIndex() + " (records " + chunk.getStartRecord() + "–"
                            + (chunk.getStartRecord() + chunk.getRecordCount() - 1) + ") failed after "
                            + chunk.getAttempts() + " attempts: " + chunk.getLastError() + " — "
                            + handle.live.getFailureCount() + " failure(s) since your last ack; "
                            + "the loop continues; decide whether to fix and re-run the failed chunks.");
        }
    }

    /**
     * Failure-cooldown gate (under the handle lock): wake at most once per
     * {@code failureCooldownSeconds}. Every failed chunk still lands in the
     * ledger and bumps the counter — only the notification is throttled.
     */
    private boolean failureWakeupDue(Handle handle) {
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0, handle.live.getFailureCooldownSeconds()) * 1000L;
        if (now - handle.lastFailureWakeupAtMs < cooldownMs) {
            return false;
        }
        handle.lastFailureWakeupAtMs = now;
        return true;
    }

    // ──────────────────── Worker model gate ────────────────────

    /**
     * Resolves the worker model exactly as the LightLm call would: recipe →
     * {@code params.model} → alias cascade. Pure read — callers persist
     * {@code state.resolvedWorkerModel} when they want the transparency trail.
     */
    public String resolveWorkerModel(ThinkProcessDocument process, WowbaggerState state) {
        String recipeName = firstNonBlank(state.getWorkerRecipe(), DEFAULT_WORKER_RECIPE);
        de.mhus.vance.brain.recipe.ResolvedRecipe recipe = recipeResolver
                .resolve(process.getTenantId(), process.getProjectId(), recipeName)
                .orElseThrow(() -> new IllegalStateException(
                        "worker recipe '" + recipeName + "' not found in the recipe cascade"));
        String spec = de.mhus.vance.brain.ai.AiModelResolver.parseModelSpec(recipe.params());
        de.mhus.vance.brain.ai.AiModelResolver.Resolved resolved =
                aiModelResolver.resolveOrDefault(spec, process.getTenantId(), process.getProjectId(), process.getId());
        return resolved.providerInstance() + ":" + resolved.modelName();
    }

    /**
     * The cost gate: refuses a run whose resolved worker model is not on the
     * allowlist (setting {@value #ALLOWED_MODELS_KEY}). Called at start and on
     * re-configure — approval is the operator's setting, never the agent's own
     * decision.
     */
    public void checkModelApproval(ThinkProcessDocument process, WowbaggerState state) {
        String model = resolveWorkerModel(process, state);
        state.setResolvedWorkerModel(model);
        String allowlist = allowedModels(process);
        if (!modelApproved(model, allowlist)) {
            throw new IllegalStateException("worker model '" + model
                    + "' is not approved for Wowbagger — approve it via setting '"
                    + ALLOWED_MODELS_KEY
                    + "' (comma-separated patterns, * wildcards, matched against"
                    + " 'providerInstance:modelName' or the bare model name; configured at project"
                    + " or _tenant scope"
                    + (allowlist == null
                            ? "; currently NO allowlist is set (fail-closed)"
                            : "; current allowlist: '" + allowlist + "'")
                    + ")");
        }
    }

    /** Non-throwing variant for status views and the {@code //wowbagger} diagnostic. */
    public boolean isWorkerModelApproved(ThinkProcessDocument process, WowbaggerState state) {
        try {
            return modelApproved(resolveWorkerModel(process, state), allowedModels(process));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The allowlist setting, read without think-process scope: project first,
     * then the tenant's {@code _tenant} project. {@code null} = nothing approved
     * (fail-closed — a missing allowlist refuses every run).
     */
    private @org.jspecify.annotations.Nullable String allowedModels(ThinkProcessDocument process) {
        String v = settingService.getStringValue(
                process.getTenantId(),
                de.mhus.vance.shared.settings.SettingService.SCOPE_PROJECT,
                process.getProjectId(),
                ALLOWED_MODELS_KEY);
        if (v == null || v.isBlank()) {
            v = settingService.getStringValue(
                    process.getTenantId(),
                    de.mhus.vance.shared.settings.SettingService.SCOPE_PROJECT,
                    de.mhus.vance.shared.home.HomeBootstrapService.TENANT_PROJECT_NAME,
                    ALLOWED_MODELS_KEY);
        }
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * Pattern match: comma-separated patterns, {@code *} wildcards,
     * case-insensitive, against the full {@code providerInstance:modelName}
     * and the bare {@code modelName} alike — {@code sipgate-coding-pro} and
     * {@code coding-proxy:*} both work. A {@code null}/blank allowlist approves
     * nothing (fail-closed).
     */
    static boolean modelApproved(String resolvedModel, @org.jspecify.annotations.Nullable String allowlistCsv) {
        if (allowlistCsv == null || allowlistCsv.isBlank()) {
            return false;
        }
        int colon = resolvedModel.indexOf(':');
        String bare = colon >= 0 ? resolvedModel.substring(colon + 1) : resolvedModel;
        for (String raw : allowlistCsv.split(",")) {
            String pattern = raw.trim().toLowerCase();
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.equals("*")) {
                return true;
            }
            java.util.regex.Pattern regex =
                    java.util.regex.Pattern.compile("\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E");
            if (regex.matcher(resolvedModel.toLowerCase()).matches()
                    || regex.matcher(bare.toLowerCase()).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The run's persistent RootDir — source and results live here (§ run
     * root). Resolution order:
     * <ol>
     *   <li>{@code state.workTargetName} set and still present → use it
     *       (restart/resume keeps the same dir);</li>
     *   <li>process-wide WORK target already names a RootDir → adopt it
     *       (an explicit pin wins over auto-creation);</li>
     *   <li>else create {@code wowbagger-<processId prefix>} (adopted when a
     *       previous run created it). Persistent — {@code deleteOnCreatorClose}
     *       stays {@code false}, a long run survives agent turns, restarts
     *       and process closes.</li>
     * </ol>
     * Idempotent; safe to call from configure and start alike.
     */
    public String ensureWorkRoot(ThinkProcessDocument process, WowbaggerState state) {
        String tenantId = process.getTenantId();
        String projectId = process.getProjectId();
        String named = state.getWorkTargetName();
        if (named != null
                && !named.isBlank()
                && workspaceService.getRootDir(tenantId, projectId, named).isPresent()) {
            return named;
        }
        String wanted = named != null && !named.isBlank()
                ? named
                : workTargetService.current(process).kind() == WorkTargetKind.WORK
                        ? nullIfBlank(workTargetService.current(process).targetName())
                        : null;
        if (wanted == null) {
            wanted = RUN_DIR_PREFIX + safeIdPart(process.getId(), RUN_DIR_ID_CHARS);
        }
        if (workspaceService.getRootDir(tenantId, projectId, wanted).isPresent()) {
            state.setWorkTargetName(wanted);
            return wanted; // adopt: previous run with this id or the pinned root
        }
        de.mhus.vance.shared.workspace.RootDirHandle handle =
                workspaceService.createRootDir(de.mhus.vance.shared.workspace.RootDirSpec.builder()
                        .tenantId(tenantId)
                        .projectId(projectId)
                        .type(de.mhus.vance.shared.workspace.EphemeralHandler.TYPE)
                        .creatorProcessId(process.getId())
                        .creatorEngine("wowbagger")
                        .labelHint(wanted)
                        .deleteOnCreatorClose(false)
                        .build());
        state.setWorkTargetName(handle.getDirName());
        log.info("Wowbagger pool id='{}' created run RootDir '{}'", process.getId(), handle.getDirName());
        return handle.getDirName();
    }

    private static @Nullable String nullIfBlank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Process ids are hex; keep it label-safe regardless. */
    private static String safeIdPart(String id, int maxChars) {
        StringBuilder sb = new StringBuilder(maxChars);
        for (char c : id.toCharArray()) {
            if (sb.length() >= maxChars) {
                break;
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.length() > 0 ? sb.toString() : "run";
    }

    /**
     * Ephemeral-source warnings for {@code wowbagger_configure} (live-run
     * lesson: a source inside a temp RootDir dies with its creator — a
     * multi-hour run then grinds into "source lost"). Read-only: unlike
     * {@link #resolveWorkDirName} this never CREATES a temp RootDir.
     */
    public List<String> sourceRootWarnings(ThinkProcessDocument process, WowbaggerState state) {
        if (state.getSourcePath() == null || state.getSourcePath().isBlank()) {
            return List.of();
        }
        WorkTarget target = workTargetService.current(process);
        if (target.kind() != WorkTargetKind.WORK) {
            return List.of();
        }
        String dirName = target.targetName();
        if (dirName == null) {
            dirName = workspaceService
                    .getWorkingDir(process.getTenantId(), process.getProjectId(), process.getId())
                    .orElse(null);
            if (dirName == null) {
                return List.of("the source resolves into a process-temp RootDir — it dies with"
                        + " the process (no resume after close, and a brain restart may dispose it)."
                        + " For long runs use a named RootDir (workTarget targetName) and put the"
                        + " source there.");
            }
        }
        final String resolvedDir = dirName;
        return workspaceService
                .getRootDir(process.getTenantId(), process.getProjectId(), resolvedDir)
                .filter(h -> h.deleteOnCreatorClose() || h.creatorProcessId() != null)
                .map(h -> List.of("the source lives in the temp RootDir '" + resolvedDir + "' (creator '"
                        + h.creatorProcessId() + "', deleteOnCreatorClose) — it dies when its creator"
                        + " closes. For long runs move the source into a named RootDir."))
                .orElse(List.of());
    }

    /**
     * Heartbeat: while the run is grinding, wake the agent for a check-in
     * when nothing else produced news for {@code wakeEverySeconds}. Progress
     * and failure wakeups refresh the base, so a chatty run never heartbeats
     * — this is for long silent stretches (provider stalls, slow chunks).
     */
    private void maybeHeartbeat(ThinkProcessDocument process, Handle handle, WowbaggerState state) {
        int every = Math.max(0, state.getWakeEverySeconds());
        if (every == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - handle.lastWakeupAtMs < every * 1000L) {
            return;
        }
        wakeup(
                process,
                "heartbeat (" + every + "s without news): " + describe(handle) + ", " + state.getRecordsDone()
                        + " of " + state.getRecordsTotal() + " committed (" + percent(state) + "), "
                        + state.getFailedChunks().size() + " failed chunk(s) — check whether the run is still "
                        + "healthy (provider rate limits, output quality).");
    }

    private void maybeWakeOnProgress(ThinkProcessDocument process, WowbaggerState state) {
        int every = Math.max(0, state.getWakeEveryRecords());
        if (every == 0) {
            return;
        }
        long done = state.getRecordsDone();
        if (done / every > (done - 1) / every || done == state.getRecordsTotal()) {
            wakeup(
                    process,
                    "progress: " + done + " of " + state.getRecordsTotal() + " records committed (" + percent(state)
                            + ")");
        }
    }

    private String percent(WowbaggerState state) {
        if (state.getRecordsTotal() <= 0) {
            return "?%";
        }
        return Math.round(state.getRecordsDone() * 100.0 / state.getRecordsTotal()) + "%";
    }

    private String describe(Handle handle) {
        WowbaggerState s = handle.live;
        return s.getThreadsDesired() + " thread(s) desired";
    }

    // ──────────────────── Finish / merge ────────────────────

    /**
     * Run completion (drained, all workers idle): merges the chunk docs in
     * order into the result document (idempotent full write), marks the
     * structure finished and sends the finish wakeup.
     */
    private void finish(ThinkProcessDocument process, Handle handle, WowbaggerState state) {
        synchronized (handle.lock) {
            if (!state.isFinished()) {
                mergeResults(process, state);
                state.setFinished(true);
                persist(process, state);
            }
            handle.running = false;
        }
        wakeup(
                process,
                "run finished — " + state.getRecordsDone() + " of " + state.getRecordsTotal()
                        + " records committed, " + state.getCounters().getFailures()
                        + " failed chunk(s), merged result at `"
                        + state.getOutputDocPath() + "`");
    }

    /** Ordered concatenation of the chunk docs into the result document (§6.5). */
    public void mergeResults(ThinkProcessDocument process, WowbaggerState state) {
        StringBuilder merged = new StringBuilder();
        int gaps = 0;
        for (int index = 0; index < Math.max(state.getChunksTotal(), 0); index++) {
            Optional<DocumentDocument> doc = documentService.findByPath(
                    process.getTenantId(),
                    process.getProjectId(),
                    chunkDocPath(process.getId(), index, outputFormat(state)));
            if (doc.isEmpty()) {
                gaps++;
                continue;
            }
            try {
                String content = documentService.readContent(doc.get());
                if (content != null && !content.isBlank()) {
                    merged.append(content.stripTrailing()).append('\n');
                }
            } catch (RuntimeException e) {
                gaps++;
                log.warn(
                        "Wowbagger pool id='{}' merge could not read chunk #{}: {}",
                        process.getId(),
                        index,
                        e.toString());
            }
        }
        String path = firstNonBlank(state.getOutputDocPath(), defaultResultPath(process));
        String existing = documentService
                .findByPath(process.getTenantId(), process.getProjectId(), path)
                .map(DocumentDocument::getId)
                .orElse(null);
        var actor = de.mhus.vance.shared.permission.WriteActor.SYSTEM;
        if (existing == null) {
            documentService.createText(
                    process.getTenantId(),
                    process.getProjectId(),
                    path,
                    "Wowbagger result",
                    List.of("wowbagger", "result"),
                    merged.toString(),
                    "wowbagger:" + process.getId(),
                    actor);
        } else {
            documentService.update(existing, "Wowbagger result", null, merged.toString(), null, actor);
        }
        if (gaps > 0) {
            log.warn("Wowbagger pool id='{}' merge finished with {} gap(s)", process.getId(), gaps);
        }
    }

    // ──────────────────── Worker call / publish ────────────────────

    private String outputFormat(WowbaggerState state) {
        return firstNonBlank(state.getOutputFormat(), state.getInputFormat(), FORMAT_JSONL);
    }

    /** Calls the worker recipe with the chunk's records; validation on the worker thread. */
    private List<String> callWorker(ThinkProcessDocument process, WowbaggerState state, List<String> records)
            throws WorkerReplyException {
        StringBuilder prompt = new StringBuilder("## Task\n")
                .append(state.getTask())
                .append("\n\n## Records (")
                .append(records.size())
                .append(" total, one per line, in order)\n")
                .append(String.join("\n", records));
        metricService.counter(METRIC_LLM_CALLS).increment();
        state.getCounters().setWorkerCalls(state.getCounters().getWorkerCalls() + 1);
        String reply = lightLlmService.call(LightLlmRequest.builder()
                .recipeName(firstNonBlank(state.getWorkerRecipe(), DEFAULT_WORKER_RECIPE))
                .userPrompt(prompt.toString())
                .tenantId(process.getTenantId())
                .projectId(process.getProjectId())
                .processId(process.getId())
                .build());
        return validateWorkerReply(reply, records.size(), FORMAT_JSONL.equals(outputFormat(state)), objectMapper);
    }

    /** Validates a worker reply: one output line per record; JSONL per line an object. */
    static List<String> validateWorkerReply(String reply, int expectedCount, boolean jsonl, ObjectMapper om)
            throws WorkerReplyException {
        List<String> lines =
                reply.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (lines.size() != expectedCount) {
            throw new WorkerReplyException("expected " + expectedCount + " output lines, got " + lines.size());
        }
        if (jsonl) {
            for (String line : lines) {
                try {
                    om.readValue(line, Map.class);
                } catch (JacksonException e) {
                    throw new WorkerReplyException("output line is not a JSON object: " + truncate(line, 120), e);
                }
            }
        }
        return lines;
    }

    /** A structurally invalid worker reply — a failed attempt, not data. */
    static class WorkerReplyException extends RuntimeException {
        WorkerReplyException(String message) {
            super(message);
        }

        WorkerReplyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void publishChunk(
            ThinkProcessDocument process, WowbaggerState state, WowbaggerState.WaveChunk chunk, List<String> lines) {
        String path = chunkDocPath(process.getId(), chunk.getIndex(), outputFormat(state));
        String content = String.join("\n", lines) + "\n";
        try {
            documentService.createText(
                    process.getTenantId(),
                    process.getProjectId(),
                    path,
                    chunkTitle(chunk),
                    List.of("wowbagger", "chunk"),
                    content,
                    "wowbagger:" + process.getId(),
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        } catch (DocumentAlreadyExistsException e) {
            log.debug("Wowbagger pool id='{}' adopting existing chunk doc '{}'", process.getId(), path);
        }
    }

    static String chunkDocPath(String processId, int index, @Nullable String format) {
        String suffix = FORMAT_JSONL.equals(firstNonBlank(format, FORMAT_JSONL)) ? ".jsonl" : ".txt";
        return RUN_FOLDER_PREFIX + "/" + processId + "/results/chunk-" + String.format("%06d", index) + suffix;
    }

    private static String chunkTitle(WowbaggerState.WaveChunk chunk) {
        return "Chunk " + String.format("%06d", chunk.getIndex());
    }

    String defaultResultPath(ThinkProcessDocument process) {
        return RUN_FOLDER_PREFIX + "/" + process.getId() + "/result";
    }

    /**
     * Persists an externally-mutated structure (the wowbagger_configure tool)
     * — the single serialization path tweakable structure fields share with
     * the pool's own writes.
     */
    public void persistStructure(ThinkProcessDocument process, WowbaggerState state) {
        Handle handle = handle(process.getId());
        synchronized (handle.lock) {
            handle.live = state;
            persist(process, state);
        }
    }

    // ──────────────────── Wakeups ────────────────────

    /**
     * Wakes the agent: a chat-history note (the vermerk, visible even before
     * the agent turn) plus a pending message that auto-wakes the agent's lane.
     */
    private void wakeup(ThinkProcessDocument process, String note) {
        handle(process.getId()).lastWakeupAtMs = System.currentTimeMillis();
        if (liveStatus(process.getId()) == ThinkProcessStatus.CLOSED) {
            return;
        }
        try {
            chatMessageService.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("[pool] " + note)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Wowbagger pool id='{}' chat note failed: {}", process.getId(), e.toString());
        }
        try {
            PendingMessageDocument message = new PendingMessageDocument();
            message.setContent("[pool] " + note);
            message.setFromUser("wowbagger-pool");
            thinkProcessService.appendPending(process.getId(), message, "");
        } catch (RuntimeException e) {
            log.warn("Wowbagger pool id='{}' wakeup failed: {}", process.getId(), e.toString());
        }
    }

    // ──────────────────── Source / reconcile / state ────────────────────

    /**
     * The wave reconcile (§6.3), on start: doc present → adopted (recordsDone
     * grows), doc missing → requeued for the rotation. The structure is the
     * agent's single source of configuration here.
     */
    private void reconcileWave(ThinkProcessDocument process, WowbaggerState state) {
        if (state.getWave().isEmpty()) {
            return;
        }
        int adopted = 0;
        List<WowbaggerState.WaveChunk> missing = new ArrayList<>();
        for (WowbaggerState.WaveChunk chunk : new ArrayList<>(state.getWave())) {
            boolean present = documentService
                    .findByPath(
                            process.getTenantId(),
                            process.getProjectId(),
                            chunkDocPath(process.getId(), chunk.getIndex(), outputFormat(state)))
                    .isPresent();
            if (present) {
                state.setRecordsDone(state.getRecordsDone() + chunk.getRecordCount());
                adopted++;
                state.getWave().remove(chunk);
            } else {
                missing.add(chunk);
            }
        }
        state.getWave().removeAll(missing);
        // Missing claims re-enter the rotation via the retry queue — the
        // pointer cannot go back (later chunks may be committed already).
        for (WowbaggerState.WaveChunk chunk : missing) {
            state.getRetryQueue().add(chunk);
        }
        if (adopted > 0 || !missing.isEmpty()) {
            log.info(
                    "Wowbagger pool id='{}' reconcile: {} adopted, {} requeued",
                    process.getId(),
                    adopted,
                    missing.size());
        }
    }

    private Path resolveSource(ThinkProcessDocument process, WowbaggerState state) throws IOException {
        String dirName = nullIfBlank(state.getWorkTargetName());
        if (dirName == null) {
            dirName = resolveWorkDirName(process);
        }
        if (dirName == null) {
            throw new IOException("work target is not WORK");
        }
        return workspaceService.resolve(process.getTenantId(), process.getProjectId(), dirName, state.getSourcePath());
    }

    private @Nullable String resolveWorkDirName(ThinkProcessDocument process) {
        WorkTarget target = workTargetService.current(process);
        if (target.kind() != WorkTargetKind.WORK) {
            return null;
        }
        if (target.targetName() != null) {
            return target.targetName();
        }
        return workspaceService
                .getWorkingDir(process.getTenantId(), process.getProjectId(), process.getId())
                .orElseGet(() -> workspaceService
                        .getOrCreateTempRootDir(process.getTenantId(), process.getProjectId(), process.getId())
                        .getDirName());
    }

    private static long countLines(Path source) throws IOException {
        try (var lines = Files.lines(source, StandardCharsets.UTF_8)) {
            return lines.count();
        }
    }

    static List<String> readWindowAt(Path source, long startRecord, int count) throws IOException {
        List<String> out = new ArrayList<>(count);
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            for (long i = 0; i < startRecord && reader.readLine() != null; i++) {
                // skip
            }
            String line;
            while (out.size() < count && (line = reader.readLine()) != null) {
                out.add(line);
            }
        }
        return out;
    }

    // ──────────────────── State persistence ────────────────────

    private void persist(ThinkProcessDocument process, WowbaggerState state) {
        Map<String, Object> params = thinkProcessService
                .findById(process.getId())
                .map(ThinkProcessDocument::getEngineParams)
                .orElse(null);
        Map<String, Object> p =
                params == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(params);
        // Hard-pin the process-wide WORK target to the run root while its
        // name is still blank: the agent's file_* tools then default into
        // the same root the pool reads. An explicit named pin and a CLIENT
        // target (foot session — direct client file access, the run root is
        // addressed via the tools' dirName param) stay untouched.
        if (nullIfBlank(state.getWorkTargetName()) != null) {
            WorkTarget current = workTargetService.current(process);
            if (current.kind() == WorkTargetKind.WORK && nullIfBlank(current.targetName()) == null) {
                p.put(WorkTarget.KEY, new WorkTarget(WorkTargetKind.WORK, state.getWorkTargetName()).toMap());
            }
        }
        p.put(ENGINE_STATE_KEY, objectMapper.convertValue(state, Map.class));
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }

    WowbaggerState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) {
            return new WowbaggerState();
        }
        Object raw = p.get(ENGINE_STATE_KEY);
        if (raw == null) {
            return new WowbaggerState();
        }
        WowbaggerState state = objectMapper.convertValue(raw, WowbaggerState.class);
        return state == null ? new WowbaggerState() : normalize(state);
    }

    /** Tolerant load (Zaphod lesson): normalize lists, never invent state. */
    static WowbaggerState normalize(WowbaggerState state) {
        if (state.getWave() == null) {
            state.setWave(new ArrayList<>());
        }
        if (state.getFailedChunks() == null) {
            state.setFailedChunks(new ArrayList<>());
        }
        if (state.getRetryQueue() == null) {
            state.setRetryQueue(new ArrayList<>());
        }
        if (state.getCounters() == null) {
            state.setCounters(new WowbaggerState.Counters());
        }
        return state;
    }

    private ThinkProcessStatus liveStatus(String processId) {
        return thinkProcessService
                .findById(processId)
                .map(ThinkProcessDocument::getStatus)
                .orElse(ThinkProcessStatus.CLOSED);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private static @Nullable String firstNonBlank(@Nullable String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
