package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ContextToolsApi#activePromptHints} — the engine's
 * source for the "Tool usage notes" system block. Hints come from
 * {@link Tool#promptHint()}, deduplicated by content (one entry per
 * unique hint, not per sub-tool), and skipped when blank.
 */
class ContextToolsApiPromptHintTest {

    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "tenant", "project", "session", "process", "user");

    @Test
    void empty_dispatcher_yields_empty_hints() {
        when(dispatcher.resolveAll(any())).thenReturn(List.of());

        ContextToolsApi api = new ContextToolsApi(dispatcher, ctx);
        assertThat(api.activePromptHints()).isEmpty();
    }

    @Test
    void hints_collected_from_resolveAll_and_deduplicated() {
        // Two sub-tools of one pack share the same hint; a different
        // pack contributes a second hint. Empty hints are dropped.
        Tool jiraA = stub("jira__a", "cloudId is auto-injected");
        Tool jiraB = stub("jira__b", "cloudId is auto-injected"); // dup
        Tool gmail = stub("gmail__send", "Drafts use status='draft', sends use 'sent'.");
        Tool plain = stub("plain", "");
        when(dispatcher.resolveAll(any())).thenReturn(List.of(
                resolved(jiraA), resolved(jiraB), resolved(gmail), resolved(plain)));

        ContextToolsApi api = new ContextToolsApi(dispatcher, ctx);
        assertThat(api.activePromptHints())
                .containsExactly(
                        "cloudId is auto-injected",
                        "Drafts use status='draft', sends use 'sent'.");
    }

    @Test
    void blank_only_pack_yields_empty_list() {
        Tool a = stub("only_a", "");
        Tool b = stub("only_b", "   \n  ");  // whitespace
        when(dispatcher.resolveAll(any())).thenReturn(List.of(resolved(a), resolved(b)));

        ContextToolsApi api = new ContextToolsApi(dispatcher, ctx);
        assertThat(api.activePromptHints()).isEmpty();
    }

    @Test
    void hints_are_trimmed_to_strip_yaml_block_padding() {
        Tool t = stub("padded", "\n\n   Hint with surrounding blank lines\n\n");
        when(dispatcher.resolveAll(any())).thenReturn(List.of(resolved(t)));

        ContextToolsApi api = new ContextToolsApi(dispatcher, ctx);
        assertThat(api.activePromptHints())
                .containsExactly("Hint with surrounding blank lines");
    }

    // ─── Helpers ───

    private static Tool stub(String name, String hint) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public boolean primary() { return false; }
            @Override public boolean deferred() { return false; }
            @Override public String promptHint() { return hint; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Map<String, Object> invoke(
                    Map<String, Object> p, ToolInvocationContext c) {
                return Map.of();
            }
        };
    }

    private static ToolDispatcher.Resolved resolved(Tool tool) {
        return new ToolDispatcher.Resolved(tool, NOOP_SOURCE);
    }

    private static final ToolSource NOOP_SOURCE = new ToolSource() {
        @Override public String sourceId() { return "test"; }
        @Override public List<Tool> tools(ToolInvocationContext ctx) { return List.of(); }
        @Override public java.util.Optional<Tool> find(String n, ToolInvocationContext ctx) {
            return java.util.Optional.empty();
        }
    };
}
