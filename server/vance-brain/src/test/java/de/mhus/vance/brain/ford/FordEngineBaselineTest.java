package de.mhus.vance.brain.ford;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins Ford's engine-default tool set — the baseline every Ford recipe
 * starts from before {@code allowedToolsAdd/Defer/Remove} apply.
 *
 * <p>The set is computed as {@code (engineDefault ∪ recipe.add) ∖
 * recipe.remove}: a tool missing from this non-empty baseline is excluded
 * outright, not merely undiscovered — no {@code tool_list} or
 * {@code how_do_i} call reaches past it. That is why additions here are
 * regression-tested: when a read-side essential (settings, memory, the
 * document read family) silently drops out of the baseline, Ford recipes
 * keep compiling and keep passing — the worker just loses the tool.
 *
 * <p>Metadata-only test: the engine is never started, so all constructor
 * dependencies are {@code null}. The constructor call is positional on
 * purpose — a signature change must break the compile here, not silently
 * shift the set.
 */
class FordEngineBaselineTest {

    private final Ford engine = new Ford(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);

    @Test
    void allowedTools_engineBaselineSetExposed() {
        var set = engine.allowedTools();
        assertThat(set).isNotEmpty();
        // Discovery + intro essentials
        assertThat(set).contains("tool_list", "tool_description", "how_do_i", "manual_read", "tool_result_read");
        // Sub-worker spawn
        assertThat(set).contains("process_spawn", "process_status");
        // User-facing signal + basics
        assertThat(set).contains("vance_notify", "current_time", "whoami");
        // Scratchpad (process-scoped notes)
        assertThat(set).contains("scratchpad_set", "scratchpad_get", "scratchpad_list", "scratchpad_delete");
        // Read-side document operations (mutation stays per-recipe)
        assertThat(set)
                .contains(
                        "doc_read",
                        "doc_read_lines",
                        "doc_info",
                        "doc_summary",
                        "doc_list",
                        "doc_list_folders",
                        "doc_list_in_folder",
                        "doc_list_by_tag",
                        "doc_find",
                        "doc_grep",
                        "doc_grep_path",
                        "doc_link");
        // Research + web + memory
        assertThat(set)
                .contains(
                        "web_fetch",
                        "web_search",
                        "research_search",
                        "research_investigate",
                        "research_rich",
                        "research_providers",
                        "memory_search");
        // Settings read — deferred, READ-gated per call (see SettingGetTool);
        // parity with Frankie's baseline, the worker-side counterpart of the
        // creator's `@settings` family.
        assertThat(set).contains("setting_get");
        // Generic work-target wrappers + meta
        assertThat(set)
                .contains(
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
                        "work_target_get",
                        "work_target_set");
    }
}
