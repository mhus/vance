package de.mhus.vance.brain.wowbagger.worker;

import org.jspecify.annotations.Nullable;

/**
 * The part of a run's structure a worker needs — deliberately narrow, so
 * workers never see (or mutate) the pool's state. Captured at
 * {@link WowbaggerWorker#prepare}; the structure only changes while the pool
 * is stopped, so a prepared worker never goes stale mid-run.
 *
 * @param recipeName      the worker recipe (resolved default applied)
 * @param task            the run's free-text task, {@code null} when unset
 * @param maxTokens       per-call output cap override, {@code null} = recipe default
 * @param inputJsonl      records are JSON objects ({@code inputFormat: jsonl}) rather than plain lines
 * @param mergeIntoRecord workers that answer with an object hang it into the
 *                        input record instead of emitting it alone (JSONL input only)
 */
public record WorkerSpec(
        String recipeName,
        @Nullable String task,
        @Nullable Integer maxTokens,
        boolean inputJsonl,
        boolean mergeIntoRecord) {}
