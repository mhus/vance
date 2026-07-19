package de.mhus.vance.brain.damogran;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Mutable state of one background (async) compose run, keyed by {@code runId}.
 * Lives in the pod's {@link ComposeRunRegistry} (in-memory — a pod restart
 * loses it, by design). The background thread writes progress + the final
 * result; the poll endpoint reads a snapshot (+ the current exec job's tail).
 *
 * <p>Also the {@link ComposeProgress} sink handed to tasks: they report the
 * exec job backing the running task so the poll can surface its tail.
 */
public final class ComposeRun implements ComposeProgress {

    public enum Status { RUNNING, SUCCESS, FAILURE }

    private final String runId;
    private final String tenantId;
    private final String projectId;
    private final String workspaceName;
    private final Instant startedAt;
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<DamogranTaskResult> doneTasks = Collections.synchronizedList(new ArrayList<>());
    private final List<Consumer<ComposeRun>> onDone = Collections.synchronizedList(new ArrayList<>());

    private volatile Status status = Status.RUNNING;
    private volatile int currentTaskIndex = -1;
    private volatile @Nullable String currentTaskType;
    private volatile @Nullable String currentExecJobId;
    private volatile @Nullable DamogranComposeResult result;
    private volatile @Nullable String error;
    private volatile @Nullable Instant finishedAt;

    public ComposeRun(String runId, String tenantId, String projectId,
                      String workspaceName, Instant startedAt) {
        this.runId = runId;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.workspaceName = workspaceName;
        this.startedAt = startedAt;
    }

    // ──────────────────── runner-facing mutation ────────────────────

    /** A task is about to run; clears the previous task's exec-job pointer. */
    void startTask(int index, String type) {
        this.currentTaskIndex = index;
        this.currentTaskType = type;
        this.currentExecJobId = null;
    }

    /** A task finished; record its result and drop the exec-job pointer. */
    void taskDone(DamogranTaskResult taskResult) {
        doneTasks.add(taskResult);
        this.currentExecJobId = null;
    }

    /** The run finished (success or handled failure) with a final result. */
    void complete(DamogranComposeResult finalResult) {
        this.result = finalResult;
        this.status = finalResult.isSuccess() ? Status.SUCCESS : Status.FAILURE;
        finish();
    }

    /** The run threw an unexpected exception. */
    void fail(@Nullable String message) {
        this.error = message;
        this.status = Status.FAILURE;
        finish();
    }

    private void finish() {
        this.currentTaskType = null;
        this.currentExecJobId = null;
        this.finishedAt = Instant.now();
        done.countDown();
        List<Consumer<ComposeRun>> callbacks;
        synchronized (onDone) {
            callbacks = List.copyOf(onDone);
            onDone.clear();
        }
        callbacks.forEach(cb -> cb.accept(this));
    }

    /**
     * Run {@code callback} when the run reaches a terminal state — or now, if it
     * already has. Used by {@code compose_run} to push a COMPOSE_FINISHED
     * ProcessEvent to the caller so it can sleep and resume on completion.
     */
    public void onDone(Consumer<ComposeRun> callback) {
        boolean fireNow;
        synchronized (onDone) {
            fireNow = isTerminal();
            if (!fireNow) {
                onDone.add(callback);
            }
        }
        if (fireNow) {
            callback.accept(this);
        }
    }

    // ──────────────────── ComposeProgress ────────────────────

    @Override
    public void execJob(@Nullable String jobId) {
        this.currentExecJobId = jobId;
    }

    // ──────────────────── read side (poll) ────────────────────

    /** Block up to {@code millis} for the run to finish; true if it did. */
    public boolean awaitDone(long millis) throws InterruptedException {
        return done.await(millis, TimeUnit.MILLISECONDS);
    }

    public boolean isTerminal() {
        return status != Status.RUNNING;
    }

    public String runId() { return runId; }
    public String tenantId() { return tenantId; }
    public String projectId() { return projectId; }
    public String workspaceName() { return workspaceName; }
    public Instant startedAt() { return startedAt; }
    public Status status() { return status; }
    public int currentTaskIndex() { return currentTaskIndex; }
    public @Nullable String currentTaskType() { return currentTaskType; }
    public @Nullable String currentExecJobId() { return currentExecJobId; }
    public @Nullable DamogranComposeResult result() { return result; }
    public @Nullable String error() { return error; }
    public @Nullable Instant finishedAt() { return finishedAt; }

    /** Snapshot copy of the task results recorded so far. */
    public List<DamogranTaskResult> doneTasks() {
        synchronized (doneTasks) {
            return List.copyOf(doneTasks);
        }
    }
}
