package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Status of a single {@link TodoItem} in a process's TodoList.
 * See {@code readme/arthur-plan-mode.md} §3.2.
 */
@GenerateTypeScript("thinkprocess")
public enum TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}
