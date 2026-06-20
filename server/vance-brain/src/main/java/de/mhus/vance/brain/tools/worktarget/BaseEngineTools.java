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
     * Tools the work-target layer publishes for engines to pull into
     * their {@code allowedTools()}. Three groups:
     *
     * <ul>
     *   <li>12 primary generic wrappers ({@code file_*}, {@code exec_*})
     *       — these the LLM sees in the per-turn manifest.</li>
     *   <li>2 work-target meta tools ({@code work_target_get},
     *       {@code work_target_set}) — non-primary, reachable via
     *       {@code find_tools} when an exotic backend switch is
     *       needed.</li>
     *   <li>The dispatch backends themselves ({@code client_*},
     *       {@code work_file_*}, {@code work_exec_*}). The wrappers
     *       route through these via {@code ContextToolsApi.invoke},
     *       which gates against the engine's allow-set — so the
     *       backends MUST be in the same set even though they're
     *       not directly LLM-visible. They're {@code primary=false}
     *       at the per-tool level so the LLM manifest stays clean.</li>
     * </ul>
     */
    public static final Set<String> WORK_TARGET = Set.of(
            // Generic wrappers (primary)
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
            // Work-target meta (deferred)
            "work_target_get",
            "work_target_set",
            // Foot-side backends (deferred via ClientToolRegistry)
            "client_file_read",
            "client_file_write",
            "client_file_edit",
            "client_file_list",
            "client_file_find",
            "client_file_grep",
            "client_file_head_tail",
            "client_file_count",
            "client_exec_run",
            "client_exec_status",
            "client_exec_tail",
            "client_exec_kill",
            // Brain-server-side backends (deferred — primary=true on
            // the tool itself for direct power-user access via find_tools)
            "work_file_read",
            "work_file_write",
            "work_file_edit",
            "work_file_list",
            "work_file_find",
            "work_file_grep",
            "work_file_head_tail",
            "work_file_count",
            "work_file_delete",
            "work_exec_run",
            "work_exec_status",
            "work_exec_tail",
            "work_exec_kill");
}
