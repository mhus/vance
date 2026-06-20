package de.mhus.vance.brain.tools.worktarget;

import java.util.Set;

/**
 * Tool-name constants that engines can include in their
 * {@code allowedTools()} to expose the generic
 * {@link de.mhus.vance.shared.worktarget.WorkTarget}-driven file /
 * exec surface to the LLM.
 *
 * <p>Backends ({@code client_*}, {@code work_*}) are not part of
 * this set — recipes pull them in via {@code allowedToolsAdd} when
 * the LLM should reach them directly, otherwise the wrappers
 * dispatch via {@code ToolBus}.
 */
public final class BaseEngineTools {

    private BaseEngineTools() {}

    /**
     * The 14 generic tools the work-target layer publishes: 8 file
     * operations + 4 exec operations + work_target_get / set.
     */
    public static final Set<String> WORK_TARGET = Set.of(
            "file_read",
            "file_write",
            "file_edit",
            "file_list",
            "file_find",
            "file_grep",
            "file_head_tail",
            "file_count",
            "exec_run",
            "exec_status",
            "exec_tail",
            "exec_kill",
            "work_target_get",
            "work_target_set");
}
