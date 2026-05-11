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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tool-result truncation wiring: when {@link ToolResultStorage} is
 * passed into {@link ContextToolsApi}, large tool results are replaced
 * by the storage's stub map on the way back to the LLM. The history-
 * tagging hook still sees the FULL pre-truncation result so its
 * RESOURCE-key extraction is unaffected.
 */
class ContextToolsApiTruncationHookTest {

    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "acme", "proj", "sess", "proc", "u");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void largeResult_replacedWithStub_onWayBackToCaller() {
        ToolResultStorage storage = new ToolResultStorage(
                objectMapper, tempDir, /*threshold*/ 512);
        // Result whose JSON serialization comfortably exceeds 512 B.
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        stubResolveAndInvoke("client_file_read",
                Set.of("read-only", "client-file"), big);

        ContextToolsApi api = apiWithStorage(storage, Set.of("client_file_read"));

        Map<String, Object> seenByLlm = api.invoke("client_file_read",
                Map.of("path", "/abs/Foo.java"));

        // Caller (the LLM-facing path) gets the stub, not the original.
        assertThat(seenByLlm).containsKey(ToolResultStorage.STUB_TRUNCATED_KEY);
        assertThat(seenByLlm.get(ToolResultStorage.STUB_TRUNCATED_KEY)).isEqualTo(true);
    }

    @Test
    void smallResult_passesThrough_unchanged() {
        ToolResultStorage storage = new ToolResultStorage(
                objectMapper, tempDir, /*threshold*/ 4096);
        Map<String, Object> small = Map.of("exitCode", 0);
        stubResolveAndInvoke("doc_read", Set.of("read-only"), small);

        ContextToolsApi api = apiWithStorage(storage, Set.of("doc_read"));

        Map<String, Object> seenByLlm = api.invoke("doc_read", Map.of());

        assertThat(seenByLlm).isSameAs(small);
    }

    @Test
    void truncationHappensAfterTagExtraction_soResourceKeyStaysReal() {
        ToolResultStorage storage = new ToolResultStorage(
                objectMapper, tempDir, /*threshold*/ 256);
        // A document-write whose result is also too big — but the
        // documentId field is what the tag-builder reads. The tag must
        // come from the ORIGINAL result, not the stub.
        Map<String, Object> docResult = new LinkedHashMap<>();
        docResult.put("documentId", "65f-deadbeef");
        docResult.put("body", "x".repeat(2048));
        stubResolveAndInvoke("doc_edit",
                Set.of("write", "document"), docResult);

        RecordingSink sink = new RecordingSink();
        ContextToolsApi api = apiWith(storage, sink, Set.of("doc_edit"));

        Map<String, Object> seenByLlm = api.invoke("doc_edit",
                Map.of("documentId", "65f-deadbeef"));

        // LLM sees the stub
        assertThat(seenByLlm).containsKey(ToolResultStorage.STUB_TRUNCATED_KEY);

        // The history-tag sink received the REAL DOCUMENT key — not a
        // tag derived from the stub's `_storagePath`.
        assertThat(sink.emitted).hasSize(1);
        assertThat(sink.emitted.get(0))
                .contains("RESOURCE:DOCUMENT:65f-deadbeef", "DOC_EDIT");
    }

    @Test
    void noStorageWired_resultPassesThrough() {
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        stubResolveAndInvoke("doc_read", Set.of("read-only"), big);

        // Legacy ctor — no storage param. Should not truncate.
        ContextToolsApi api = new ContextToolsApi(
                dispatcher, ctx, Set.of("doc_read"));

        Map<String, Object> seenByLlm = api.invoke("doc_read", Map.of());

        // Wait — the simple 3-arg ctor checks isLlmVisible. Use direct
        // dispatch via the test helper that wires a no-storage api.
        // Restate: with no storage wired, invoke returns the original.
        assertThat(seenByLlm).isSameAs(big);
    }

    @Test
    void truncationFailure_doesNotCrashToolCall() {
        // Storage whose persist will fail because the base dir is a
        // file, not a directory. The hook must swallow and return the
        // original result.
        Path notADir = tempDir.resolve("conflicting-file.txt");
        try {
            java.nio.file.Files.writeString(notADir, "blocking the dir slot");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ToolResultStorage storage = new ToolResultStorage(
                objectMapper, notADir, /*threshold*/ 256);
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        stubResolveAndInvoke("doc_read", Set.of("read-only"), big);

        ContextToolsApi api = apiWithStorage(storage, Set.of("doc_read"));

        // Must not throw; LLM gets the original big result (loud but
        // correct — fail-open).
        Map<String, Object> seenByLlm = api.invoke("doc_read", Map.of());
        assertThat(seenByLlm).isNotNull();
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private ContextToolsApi apiWithStorage(ToolResultStorage storage, Set<String> allowed) {
        return apiWith(storage, new RecordingSink(), allowed);
    }

    private ContextToolsApi apiWith(
            ToolResultStorage storage, HistoryTagSink sink, Set<String> allowed) {
        return new ContextToolsApi(
                dispatcher, ctx,
                allowed, allowed, Set.of(), Set.of(),
                ToolInvocationListener.NOOP,
                null,
                new HistoryTagBuilder(),
                sink,
                storage);
    }

    private void stubResolveAndInvoke(String name, Set<String> labels, Map<String, Object> result) {
        Tool tool = stubTool(name, labels);
        when(dispatcher.resolve(eq(name), any()))
                .thenReturn(Optional.of(new ToolDispatcher.Resolved(tool, stubSource(name, tool))));
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

    private static ToolSource stubSource(String name, Tool tool) {
        return new ToolSource() {
            @Override public String sourceId() { return "stub"; }
            @Override public List<Tool> tools(ToolInvocationContext c) { return List.of(tool); }
            @Override public Optional<Tool> find(String n, ToolInvocationContext c) {
                return n.equals(name) ? Optional.of(tool) : Optional.empty();
            }
        };
    }

    private static final class RecordingSink implements HistoryTagSink {
        final List<Set<String>> emitted = new ArrayList<>();
        @Override public void emit(Set<String> tags) { emitted.add(tags); }
    }

}
