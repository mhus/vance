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
     *   <li>13 primary generic wrappers ({@code file_*}, {@code exec_*})
     *       — these the LLM sees in the per-turn manifest. {@code
     *       file_delete} is primary although both its backends are
     *       deferred: a deferred backend is only acceptable because a
     *       visible wrapper covers its purpose, so deferring the wrapper
     *       too would make deleting unreachable in practice.</li>
     *   <li>2 work-target meta tools ({@code work_target_get},
     *       {@code work_target_set}) — non-primary, reachable via
     *       {@code tool_list} when an exotic backend switch is
     *       needed.</li>
     *   <li>The dispatch backends themselves ({@code client_*},
     *       {@code work_file_*}, {@code work_exec_*}). The wrappers
     *       route through these via {@code ContextToolsApi.invokeDelegate},
     *       which gates against the engine's allow-set — so the
     *       backends MUST be in the same set even though they're
     *       not directly LLM-visible.</li>
     * </ul>
     *
     * <p><b>Backends are {@code deferred=true} + {@code primary=false}.</b>
     * Both flags are needed and they do different jobs: {@code deferred}
     * decides the per-turn bucket in {@code ContextToolsApi.classify}
     * (which never consults {@code primary}), while {@code primary}
     * governs the discovery surfaces — {@code tool_list}'s default view,
     * the {@code how_do_i} catalogue, and the unrestricted-engine
     * fallback in {@code visibleResolved}. Setting only one leaves the
     * backend advertised on the other side.
     *
     * <p>The point is that the LLM should never have to choose a side:
     * that is what the wrapper decides from the work target. Two equally
     * advertised candidates for one job measurably split model choice —
     * see {@code planning/tool-surface-followups.md} (C-run, {@code
     * exec_run}↔{@code work_exec_run}) and {@code
     * planning/tool-naming-sweep.md} §2.
     *
     * <p>Excluded on purpose: {@code work_exec_check}, {@code
     * work_exec_stat}, {@code client_exec_stat}. They have no wrapper, so
     * deferring them would make them unreachable rather than redundant.
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
            "file_delete",
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
            "client_file_delete",
            "client_exec_run",
            "client_exec_status",
            "client_exec_tail",
            "client_exec_kill",
            // Brain-server-side backends (deferred — primary=true on
            // the tool itself for direct power-user access via tool_list)
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
