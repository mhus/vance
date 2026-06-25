package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.thinkprocess.TodoStatus;
import org.jspecify.annotations.Nullable;

/**
 * Partial-mutate payload for
 * {@link ThinkProcessService#updateTodos(String, java.util.List)}.
 * Fields that are {@code null} are left unchanged on the persisted
 * {@code TodoItem}; non-null fields overwrite.
 *
 * <p>{@code id} is the lookup key and may not be {@code null}; the
 * service skips patches whose {@code id} does not match an existing
 * item without raising an error (LLM-supplied stale references must
 * not crash the turn).
 *
 * <p>Lives in {@code vance-shared} rather than {@code vance-api} on
 * purpose: this is an engine-internal mutator contract, not a wire
 * payload the LLM or external clients see. Tools build it from
 * parsed JSON before calling the service.
 */
public record TodoPatch(
        String id,
        @Nullable TodoStatus status,
        @Nullable String content,
        @Nullable String activeForm) {}
