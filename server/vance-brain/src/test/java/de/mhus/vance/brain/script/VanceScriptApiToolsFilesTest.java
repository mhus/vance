package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the tool-discovery surface ({@code vance.tools.list/has}) and the
 * capability-guarded {@code vance.files} adapter of {@link VanceScriptApi}.
 */
class VanceScriptApiToolsFilesTest {

    private static ContextToolsApi toolsWith(Set<String> allowed) {
        ContextToolsApi tools = mock(ContextToolsApi.class);
        when(tools.scope()).thenReturn(new ToolInvocationContext("t", "p", "s", "proc", "u"));
        when(tools.invocableToolNames()).thenReturn(allowed);
        when(tools.isAllowed(any())).thenAnswer(inv -> allowed.contains(inv.getArgument(0)));
        return tools;
    }

    @Test
    void files_isEnabled_true_whenFileReadAllowed() {
        VanceScriptApi api = new VanceScriptApi(
                toolsWith(Set.of("file_read", "file_write")), null, Set.of());

        assertThat(api.files.isEnabled()).isTrue();
    }

    @Test
    void files_isEnabled_false_whenNoFileTools() {
        VanceScriptApi api = new VanceScriptApi(toolsWith(Set.of("web_search")), null, Set.of());

        assertThat(api.files.isEnabled()).isFalse();
    }

    @Test
    void files_read_throws_whenDisabled() {
        VanceScriptApi api = new VanceScriptApi(toolsWith(Set.of("web_search")), null, Set.of());

        assertThatThrownBy(() -> api.files.read("x.txt"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void files_write_delegatesTo_file_write_tool() {
        ContextToolsApi tools = toolsWith(Set.of("file_read", "file_write"));
        when(tools.invoke(eq("file_write"), any())).thenReturn(Map.of("ok", true));
        VanceScriptApi api = new VanceScriptApi(tools, null, Set.of());

        Map<String, Object> result = api.files.write("out.txt", "hi");

        assertThat(result).containsEntry("ok", true);
        // The bound process's tool + WorkTarget routing does the actual write.
        org.mockito.Mockito.verify(tools)
                .invoke(eq("file_write"), eq(Map.of("path", "out.txt", "content", "hi")));
    }

    @Test
    void tools_has_respectsDeniedNames() {
        VanceScriptApi api = new VanceScriptApi(
                toolsWith(Set.of("file_read", "process_create")), null, Set.of("process_create"));

        assertThat(api.tools.has("file_read")).isTrue();
        assertThat(api.tools.has("process_create")).isFalse();
    }

    @Test
    void tools_list_returnsAllowedMinusDenied_sorted() {
        VanceScriptApi api = new VanceScriptApi(
                toolsWith(Set.of("web_search", "file_read", "process_create")),
                null, Set.of("process_create"));

        assertThat(api.tools.list()).containsExactly("file_read", "web_search");
    }
}
