package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.history.HistoryTagBuilder;
import de.mhus.vance.brain.history.HistoryTagSink;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ContextToolsApi#withAdditional} and {@link ContextToolsApi#narrowTo}
 * return a re-shaped copy of the surface. Both used to build that copy
 * with the short constructor, which silently dropped output truncation,
 * history tags, tool health and the activation-TTL refresh.
 *
 * <p>That was not theoretical: {@code withAdditional} is exactly what
 * Arthur and Frankie call when a skill is active, so a turn with an
 * active skill lost the 32 KB result cap — the case where an oversized
 * tool result is most likely in the first place.
 */
class ContextToolsApiClonePreservesHooksTest {

    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "acme", "proj", "sess", "proc", "u");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private ContextToolsApi api(Set<String> allowed) {
        ToolResultStorage storage = new ToolResultStorage(
                objectMapper, tempDir, /*threshold*/ 512);
        return new ContextToolsApi(
                dispatcher, ctx, allowed, allowed, Set.of(), Set.of(),
                ToolInvocationListener.NOOP, null,
                new HistoryTagBuilder(), HistoryTagSink.NOOP,
                storage, null);
    }

    private void stubTool(String name, Map<String, Object> result) {
        Tool tool = mock(Tool.class);
        when(tool.name()).thenReturn(name);
        when(tool.labels()).thenReturn(Set.of("read-only"));
        when(dispatcher.resolve(eq(name), any()))
                .thenReturn(Optional.of(new ToolDispatcher.Resolved(tool, null)));
        when(dispatcher.invoke(eq(name), any(), any(), any())).thenReturn(result);
    }

    @Test
    void withAdditional_keepsOutputTruncation() {
        stubTool("doc_read", Map.of("stdout", "x".repeat(4096)));

        ContextToolsApi widened = api(Set.of("doc_read")).withAdditional(Set.of("skill_tool"));
        Map<String, Object> seenByLlm = widened.invoke("doc_read", Map.of());

        assertThat(seenByLlm)
                .as("a skill-widened surface must still cap huge results")
                .containsEntry(ToolResultStorage.STUB_TRUNCATED_KEY, true);
    }

    @Test
    void narrowTo_keepsOutputTruncation() {
        stubTool("doc_read", Map.of("stdout", "x".repeat(4096)));

        ContextToolsApi narrowed = api(Set.of("doc_read", "doc_write"))
                .narrowTo(Set.of("doc_read"));
        Map<String, Object> seenByLlm = narrowed.invoke("doc_read", Map.of());

        assertThat(seenByLlm).containsEntry(ToolResultStorage.STUB_TRUNCATED_KEY, true);
    }

    @Test
    void withAdditional_actuallyWidensTheAllowSet() {
        // Guard the behaviour the clone exists for, so a future fix of
        // the wiring cannot quietly break the widening itself.
        ContextToolsApi widened = api(Set.of("doc_read")).withAdditional(Set.of("skill_tool"));

        assertThat(widened.allowed()).contains("doc_read", "skill_tool");
    }

    @Test
    void noChange_returnsTheSameInstance() {
        ContextToolsApi base = api(Set.of("doc_read"));

        assertThat(base.withAdditional(Set.of("doc_read"))).isSameAs(base);
        assertThat(base.withAdditional(Set.of())).isSameAs(base);
    }
}
