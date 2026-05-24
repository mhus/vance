package de.mhus.vance.api.marvin;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Output of a worker's CONCLUDE-phase LLM call. Carries the
 * candidate result that VALIDATE will inspect. Optional
 * {@link #postActions} let the worker request engine-side
 * deterministic actions (e.g. document writes) once VALIDATE
 * passes.
 *
 * <p>See {@code specification/marvin-engine.md} §4.4.
 */
public record ConcludeOutput(
        String result,
        @Nullable List<PostActionSpec> postActions,
        @Nullable String reason) {}
