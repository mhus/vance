package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.tools.worktarget.BaseEngineTools;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The tool surface the worker engines share — Ford and Frankie are both task
 * workers, so they start from one definition instead of two lists that drift
 * apart (Frankie lacked the document read side and the research tools Ford
 * had, Ford lacked nothing Frankie had but its plan tools).
 *
 * <p>Two layers:
 * <ul>
 *   <li>{@link #CORE} — the engine's {@code allowedTools()}: the tools a
 *       worker needs on most tasks. These sit in the manifest (each tool's
 *       own {@code deferred()} flag still applies). Kept small on purpose:
 *       until 2026-06-21 Ford ran unrestricted, and the full catalogue in
 *       the manifest made Gemini-Flash-class models lose focus and call
 *       variants of the same operation interchangeably.</li>
 *   <li>{@link #POOL_LABELS} — everything released for workers by label
 *       ({@link ToolLabels#WORKER}) joins per turn as deferred: reachable
 *       through the discovery block, schema on demand. A new tool reaches
 *       the workers by carrying the label, not by being named here.</li>
 * </ul>
 */
public final class WorkerEngineTools {

    private WorkerEngineTools() {}

    /** Labels the worker engines take into their pool. */
    public static final Set<String> POOL_LABELS = Set.of(ToolLabels.WORKER);

    /** The shared worker core — see the class doc. */
    public static final Set<String> CORE;

    static {
        Set<String> base = new LinkedHashSet<>();
        // Discovery / introspection — the worker's way to everything not
        // in this core (the pool is listed by name + hint).
        base.add("tool_list");
        base.add("tool_description");
        base.add("how_do_i");
        base.add("manual_read");
        base.add("manual_list");
        base.add("recipe_describe");
        base.add("tool_result_read");
        // Sub-worker spawn
        base.add("process_spawn");
        base.add("process_status");
        // User-facing signal
        base.add("vance_notify");
        // Basics
        base.add("current_time");
        base.add("whoami");
        // Free-form notes across turns. All four declare deferred()==true,
        // so they cost a name + hint line each, not a schema — a worker can
        // park an intermediate finding instead of losing it to compaction.
        base.add("scratchpad_set");
        base.add("scratchpad_get");
        base.add("scratchpad_list");
        base.add("scratchpad_delete");
        // Read-side document operations. doc_read/doc_read_lines carry the
        // contentHash the doc write tools demand.
        base.add("doc_read");
        base.add("doc_read_lines");
        base.add("doc_info");
        base.add("doc_summary");
        base.add("doc_list");
        base.add("doc_list_folders");
        base.add("doc_list_in_folder");
        base.add("doc_list_by_tag");
        base.add("doc_find");
        base.add("doc_grep");
        base.add("doc_grep_path");
        base.add("doc_link");
        // Research and memory
        base.add("web_fetch");
        base.add("web_search");
        base.add("research_search");
        base.add("research_investigate");
        base.add("research_rich");
        base.add("research_providers");
        base.add("memory_search");
        // Settings read — deferred and permission-gated per call; HIDDEN
        // values come back in plain text, PASSWORD stays masked.
        // setting_set stays operator domain.
        base.add("setting_get");
        // Generic file/exec dispatch layer — wrappers plus the backends
        // they delegate to (the delegation gates on the allow-set).
        base.addAll(BaseEngineTools.WORK_TARGET);
        CORE = Collections.unmodifiableSet(base);
    }
}
