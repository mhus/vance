package de.mhus.vance.brain.history;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-functional behaviour of {@link HistoryTagBuilder}. No Spring
 * context, no mocks of Mongo or dispatcher — just verifies the
 * label-and-result → tag-set mapping.
 */
class HistoryTagBuilderTest {

    private final HistoryTagBuilder builder = new HistoryTagBuilder();

    @Test
    void onSuccess_unlabeledTool_yieldsOnlyToolCall() {
        Tool tool = stubTool(Set.of());

        Set<String> tags = builder.onSuccess("noop_tool", tool, Map.of(), Map.of(), ctx("p-1"));

        assertThat(tags).containsExactly("TOOL_CALL:noop_tool");
    }

    @Test
    void onSuccess_readOnlyTool_doesNotEmitResource() {
        Tool tool = stubTool(Set.of("read-only", "client-file"));

        Set<String> tags = builder.onSuccess("doc_read_lines",
                tool, Map.of("path", "/abs/Foo.java"),
                Map.of("path", "/abs/Foo.java"), ctx("p-1"));

        // No "write" label → no RESOURCE tag, no FILE_EDIT.
        assertThat(tags).containsExactly("TOOL_CALL:doc_read_lines");
    }

    @Test
    void onSuccess_clientFileEdit_emitsResourceAndFileEdit() {
        Tool tool = stubTool(Set.of("write", "side-effect", "client-file"));

        Set<String> tags = builder.onSuccess("client_file_edit",
                tool, Map.of("path", "/abs/path/Foo.java"),
                Map.of("path", "/abs/path/Foo.java", "replaced", 1), ctx("p-1"));

        assertThat(tags).contains(
                "TOOL_CALL:client_file_edit",
                "RESOURCE:CLIENT_FILE:/abs/path/Foo.java",
                "FILE_EDIT");
    }

    @Test
    void onSuccess_clientFileEdit_normalisesPath() {
        Tool tool = stubTool(Set.of("write", "client-file"));

        Set<String> tags = builder.onSuccess("client_file_edit",
                tool, Map.of(), Map.of("path", "/abs/./sub/../path/Foo.java"),
                ctx("p-1"));

        // Path.normalize collapses ./ and ../ segments.
        assertThat(tags).contains("RESOURCE:CLIENT_FILE:/abs/path/Foo.java");
    }

    @Test
    void onSuccess_workspaceWrite_includesProcessIdInKey() {
        Tool tool = stubTool(Set.of("write", "side-effect", "workspace"));

        Set<String> tags = builder.onSuccess("scratch_write",
                tool, Map.of("path", "notes.md"),
                Map.of("path", "notes.md"), ctx("proc-abc"));

        assertThat(tags).contains(
                "RESOURCE:WORKSPACE:proc-abc/notes.md",
                "FILE_EDIT");
    }

    @Test
    void onSuccess_workspaceWriteWithoutProcessId_skipsResource() {
        Tool tool = stubTool(Set.of("write", "workspace"));

        Set<String> tags = builder.onSuccess("scratch_write",
                tool, Map.of(), Map.of("path", "notes.md"), ctx(null));

        // No process scope → cannot build a stable WORKSPACE key.
        assertThat(tags).containsExactly("TOOL_CALL:scratch_write");
    }

    @Test
    void onSuccess_documentEdit_prefersDocumentIdOverDocId() {
        Tool tool = stubTool(Set.of("write", "document"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", "65f-canonical");
        result.put("docId", "65f-legacy");

        Set<String> tags = builder.onSuccess("doc_edit",
                tool, Map.of(), result, ctx("p-1"));

        assertThat(tags).contains(
                "RESOURCE:DOCUMENT:65f-canonical",
                "DOC_EDIT");
    }

    @Test
    void onSuccess_documentEdit_fallsBackToDocId() {
        Tool tool = stubTool(Set.of("write", "document"));

        Set<String> tags = builder.onSuccess("doc_edit",
                tool, Map.of(), Map.of("docId", "65f-legacy"), ctx("p-1"));

        assertThat(tags).contains("RESOURCE:DOCUMENT:65f-legacy", "DOC_EDIT");
    }

    @Test
    void onSuccess_documentEdit_paramsFallback_whenResultLacksId() {
        Tool tool = stubTool(Set.of("write", "document"));

        Set<String> tags = builder.onSuccess("doc_add_tag",
                tool,
                Map.of("documentId", "65f-from-params"),
                Map.of(),
                ctx("p-1"));

        assertThat(tags).contains("RESOURCE:DOCUMENT:65f-from-params");
    }

    @Test
    void onSuccess_writeToolMissingPath_skipsResourceTag() {
        Tool tool = stubTool(Set.of("write", "client-file"));

        Set<String> tags = builder.onSuccess("client_file_edit",
                tool, Map.of(), Map.of(), ctx("p-1"));

        assertThat(tags).containsExactly("TOOL_CALL:client_file_edit");
    }

    @Test
    void onSuccess_nullTool_yieldsOnlyToolCall() {
        Set<String> tags = builder.onSuccess("ghost", null, null, null, ctx("p-1"));
        assertThat(tags).containsExactly("TOOL_CALL:ghost");
    }

    @Test
    void onSuccess_blankPathString_treatedAsMissing() {
        Tool tool = stubTool(Set.of("write", "client-file"));

        Set<String> tags = builder.onSuccess("client_file_edit",
                tool, Map.of("path", "   "), Map.of("path", ""), ctx("p-1"));

        assertThat(tags).containsExactly("TOOL_CALL:client_file_edit");
    }

    @Test
    void onSuccess_documentEdit_fallsBackToNewId_forCrossDocCopy() {
        Tool tool = stubTool(Set.of("write", "document"));

        // CrossDocCopyTool returns newId (the produced copy) — not documentId.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", "65f-source");
        result.put("newId", "65f-fresh-copy");

        Set<String> tags = builder.onSuccess("doc_copy_cross_project",
                tool, Map.of(), result, ctx("p-1"));

        // newId beats sourceId — the edit landed on the new resource.
        assertThat(tags).contains("RESOURCE:DOCUMENT:65f-fresh-copy", "DOC_EDIT");
        assertThat(tags).doesNotContain("RESOURCE:DOCUMENT:65f-source");
    }

    @Test
    void onSuccess_documentEdit_fallsBackToPurgedId_forDocPurge() {
        Tool tool = stubTool(Set.of("write", "document"));

        Set<String> tags = builder.onSuccess("doc_purge",
                tool, Map.of(), Map.of("purgedId", "65f-gone"), ctx("p-1"));

        assertThat(tags).contains("RESOURCE:DOCUMENT:65f-gone", "DOC_EDIT");
    }

    @Test
    void onSuccess_documentEdit_fallsBackToId_forDocCreateKind() {
        Tool tool = stubTool(Set.of("write", "document"));

        // DocCreateKindTool returns "id".
        Set<String> tags = builder.onSuccess("doc_create_kind",
                tool, Map.of(), Map.of("id", "65f-new-doc"), ctx("p-1"));

        assertThat(tags).contains("RESOURCE:DOCUMENT:65f-new-doc", "DOC_EDIT");
    }

    @Test
    void onSuccess_documentEdit_sourceIdAlone_usedWhenNothingElsePresent() {
        Tool tool = stubTool(Set.of("write", "document"));

        // Last-resort fallback when only sourceId is present.
        Set<String> tags = builder.onSuccess("some_doc_op",
                tool, Map.of(), Map.of("sourceId", "65f-only"), ctx("p-1"));

        assertThat(tags).contains("RESOURCE:DOCUMENT:65f-only");
    }

    @Test
    void onError_emitsToolCallAndErrorTag() {
        Set<String> tags = builder.onError("client_file_edit");

        assertThat(tags).containsExactly("TOOL_CALL:client_file_edit", "ERROR");
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static Tool stubTool(Set<String> labels) {
        return new Tool() {
            @Override public String name() { return "stub"; }
            @Override public String description() { return ""; }
            @Override public boolean primary() { return false; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Set<String> labels() { return labels; }
            @Override public Map<String, Object> invoke(
                    Map<String, Object> params, ToolInvocationContext ctx) {
                return Map.of();
            }
        };
    }

    private static ToolInvocationContext ctx(@org.jspecify.annotations.Nullable String processId) {
        return new ToolInvocationContext("t", "proj", "sess", processId, "u");
    }
}
