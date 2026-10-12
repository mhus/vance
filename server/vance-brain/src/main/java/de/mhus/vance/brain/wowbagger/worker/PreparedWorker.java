package de.mhus.vance.brain.wowbagger.worker;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A worker resolved for one run: recipe, model and gates checked once, then
 * called per chunk from several pool threads at the same time —
 * implementations are immutable or synchronize themselves.
 */
public interface PreparedWorker {

    /** Worker type, for status and logs ({@code llm}, {@code zem}, {@code script}). */
    String kind();

    /** The worker recipe this was prepared from. */
    String recipeName();

    /**
     * The single provider model this worker calls ({@code providerInstance:modelName}),
     * checked against the operator allowlist before the run. {@code null} for
     * workers without one fixed model — they gate each call at runtime.
     */
    @Nullable
    String resolvedModel();

    /**
     * Process one chunk: one output line per input record, in order. Throws on
     * any failure — the pool counts it as a chunk attempt and retries.
     */
    WorkerReply process(List<String> records, ChunkContext chunk);

    /** Whether the run's free-text {@code task} drives this worker (Zem: no — the profile is the task). */
    default boolean usesTask() {
        return true;
    }

    /** Called after the chunk's document is committed — book what was accumulated. */
    default void onCommit() {}
}
