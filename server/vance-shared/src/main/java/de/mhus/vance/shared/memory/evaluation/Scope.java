package de.mhus.vance.shared.memory.evaluation;

import org.jspecify.annotations.Nullable;

/**
 * Scope of an extracted item.
 *
 * <p>{@code id} is the business identifier of the scoping entity:
 * project name, session id, task ref. For {@link ScopeKind#GLOBAL}
 * it is always {@code null}.
 */
public record Scope(ScopeKind kind, @Nullable String id) {

    public static Scope global() {
        return new Scope(ScopeKind.GLOBAL, null);
    }

    public static Scope project(String projectId) {
        return new Scope(ScopeKind.PROJECT, projectId);
    }

    public static Scope session(String sessionId) {
        return new Scope(ScopeKind.SESSION, sessionId);
    }

    public static Scope task(String taskId) {
        return new Scope(ScopeKind.TASK, taskId);
    }
}
