package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.history.HistoryTagBuilder;
import de.mhus.vance.brain.history.HistoryTagSink;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Wiring between {@link ContextToolsApi#invoke} and the history-tagging
 * hook: success path emits {@code TOOL_CALL:*} + {@code RESOURCE:*} +
 * {@code FILE_EDIT}/{@code DOC_EDIT} when the tool is labelled
 * appropriately; error path emits {@code TOOL_CALL:*} + {@code ERROR};
 * the NOOP sink path stays silent without changing behaviour.
 *
 * <p>Pure unit tests — the dispatcher is mocked. The {@link HistoryTagBuilder}
 * runs for real (it is pure-functional and already covered by its own
 * test).
 */
class ContextToolsApiHistoryHookTest {

    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "tenant", "project", "session", "process-abc", "user");

    @Test
    void successWithWriteLabel_emitsResourceAndFileEditTags() {
        stubResolveAndInvoke("client_file_edit",
                Set.of("write", "side-effect", "client-file"),
                Map.of("path", "/abs/path/Foo.java", "replaced", 1));

        RecordingSink sink = new RecordingSink();
        ContextToolsApi api = api(sink, Set.of("client_file_edit"));

        Map<String, Object> result = api.invoke("client_file_edit",
                Map.of("path", "/abs/path/Foo.java"));

        assertThat(result).containsEntry("replaced", 1);
        assertThat(sink.emitted).hasSize(1);
        assertThat(sink.emitted.get(0)).contains(
                "TOOL_CALL:client_file_edit",
                "RESOURCE:CLIENT_FILE:/abs/path/Foo.java",
                "FILE_EDIT");
    }

    @Test
    void successWithReadLabel_emitsOnlyToolCallTag() {
        stubResolveAndInvoke("doc_read_lines",
                Set.of("read-only"),
                Map.of("lines", "ok"));

        RecordingSink sink = new RecordingSink();
        ContextToolsApi api = api(sink, Set.of("doc_read_lines"));

        api.invoke("doc_read_lines", Map.of());

        assertThat(sink.emitted).hasSize(1);
        assertThat(sink.emitted.get(0)).containsExactly("TOOL_CALL:doc_read_lines");
    }

    @Test
    void successWithDocumentLabel_emitsDocumentResource() {
        stubResolveAndInvoke("doc_edit",
                Set.of("write", "document"),
                Map.of("documentId", "65f-deadbeef"));

        RecordingSink sink = new RecordingSink();
        ContextToolsApi api = api(sink, Set.of("doc_edit"));

        api.invoke("doc_edit", Map.of("documentId", "65f-deadbeef"));

        assertThat(sink.emitted.get(0)).contains(
                "TOOL_CALL:doc_edit",
                "RESOURCE:DOCUMENT:65f-deadbeef",
                "DOC_EDIT");
    }

    @Test
    void toolException_emitsToolCallAndErrorTag() {
        Tool throwing = stubTool("client_file_edit",
                Set.of("write", "client-file"));
        ToolDispatcher.Resolved resolved = new ToolDispatcher.Resolved(
                throwing, stubSource("stub", throwing));
        when(dispatcher.resolve(eq("client_file_edit"), any()))
                .thenReturn(Optional.of(resolved));
        when(dispatcher.invoke(eq("client_file_edit"), any(), any(), any()))
                .thenThrow(new ToolException("boom"));

        RecordingSink sink = new RecordingSink();
        ContextToolsApi api = api(sink, Set.of("client_file_edit"));

        assertThatThrownBy(() -> api.invoke("client_file_edit", Map.of("path", "/x")))
                .isInstanceOf(ToolException.class);

        // Single error-path emission, no resource tag (result unavailable).
        assertThat(sink.emitted).hasSize(1);
        assertThat(sink.emitted.get(0)).containsExactly("TOOL_CALL:client_file_edit", "ERROR");
    }

    @Test
    void noopSink_stillRunsToolSuccessfully() {
        stubResolveAndInvoke("doc_edit",
                Set.of("write", "document"),
                Map.of("documentId", "65f"));

        // No sink wired → falls through the legacy ctor, no side effects.
        ContextToolsApi api = new ContextToolsApi(dispatcher, ctx, Set.of("doc_edit"));

        Map<String, Object> result = api.invoke("doc_edit", Map.of("documentId", "65f"));

        assertThat(result).containsEntry("documentId", "65f");
    }

    @Test
    void faultySink_doesNotBreakToolResult() {
        stubResolveAndInvoke("client_file_edit",
                Set.of("write", "client-file"),
                Map.of("path", "/abs/Foo.java"));

        HistoryTagSink exploding = tags -> { throw new RuntimeException("sink broken"); };
        ContextToolsApi api = api(exploding, Set.of("client_file_edit"));

        // The tool call must succeed even though the sink throws.
        Map<String, Object> result = api.invoke("client_file_edit", Map.of("path", "/abs/Foo.java"));
        assertThat(result).containsEntry("path", "/abs/Foo.java");
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private ContextToolsApi api(HistoryTagSink sink, Set<String> allowed) {
        return new ContextToolsApi(
                dispatcher, ctx,
                allowed, allowed, Set.of(), Set.of(),
                ToolInvocationListener.NOOP,
                null,
                new HistoryTagBuilder(),
                sink,
                null);
    }

    private void stubResolveAndInvoke(String name, Set<String> labels,
                                      Map<String, Object> result) {
        Tool tool = stubTool(name, labels);
        when(dispatcher.resolve(eq(name), any()))
                .thenReturn(Optional.of(new ToolDispatcher.Resolved(tool, stubSource("stub", tool))));
        when(dispatcher.invoke(eq(name), any(), any(), any())).thenReturn(result);
    }

    private static Tool stubTool(String name, Set<String> labels) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public boolean primary() { return true; }
            @Override public Set<String> labels() { return labels; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Map<String, Object> invoke(
                    Map<String, Object> p, ToolInvocationContext c) { return Map.of(); }
        };
    }

    private static ToolSource stubSource(String id, Tool tool) {
        return new ToolSource() {
            @Override public String sourceId() { return id; }
            @Override public List<Tool> tools(ToolInvocationContext c) { return List.of(tool); }
            @Override public Optional<Tool> find(String n, ToolInvocationContext c) {
                return n.equals(tool.name()) ? Optional.of(tool) : Optional.empty();
            }
        };
    }

    private static final class RecordingSink implements HistoryTagSink {
        final List<Set<String>> emitted = new ArrayList<>();
        @Override public void emit(Set<String> tags) { emitted.add(tags); }
    }
}
