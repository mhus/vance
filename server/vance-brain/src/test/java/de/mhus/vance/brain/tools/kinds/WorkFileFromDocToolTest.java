package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.documents.DocumentBufferService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code work_file_from_doc} must copy the document's storage bytes
 * byte-exactly into the workspace. The old text-only path decoded the bytes
 * as UTF-8 and re-encoded them — every byte that is not valid UTF-8 became a
 * U+FFFD replacement, silently destroying binary documents (a parquet export
 * grew by ~27 % and was unreadable).
 */
class WorkFileFromDocToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);

    /** PAR1 magic plus bytes that are invalid UTF-8 — a binary document's head. */
    private static final byte[] BINARY = {0x50, 0x41, 0x52, 0x31, (byte) 0xFF, (byte) 0xFE, 0x00, 0x0D};

    private KindToolSupport support;
    private DocumentBufferService buffer;
    private DocumentService documentService;
    private WorkspaceService workspace;
    private WorkFileFromDocTool tool;
    private DocumentDocument doc;

    @BeforeEach
    void setUp() {
        support = mock(KindToolSupport.class);
        buffer = mock(DocumentBufferService.class);
        documentService = mock(DocumentService.class);
        workspace = mock(WorkspaceService.class);
        tool = new WorkFileFromDocTool(support, workspace);
        doc = mock(DocumentDocument.class);

        when(doc.getId()).thenReturn("doc-1");
        when(doc.getPath()).thenReturn("data/train.parquet");
        when(support.loadDocument(any(), any())).thenReturn(doc);
        when(support.requireInline(doc)).thenReturn(doc);
        when(support.buffer()).thenReturn(buffer);
        when(buffer.read(CTX.processId(), "doc-1")).thenReturn(doc);
        when(support.documentService()).thenReturn(documentService);
        when(documentService.loadContent(doc)).thenReturn(new ByteArrayInputStream(BINARY));
    }

    private Map<String, Object> params() {
        Map<String, Object> p = new HashMap<>();
        p.put("id", "doc-1");
        p.put("workspacePath", "train.parquet");
        p.put("dirName", "ws");
        return p;
    }

    private void writeStreamCopiesToTempFile(Path target) throws Exception {
        when(workspace.writeStream(eq("acme"), eq("proj-a"), eq("ws"), anyString(), any()))
                .thenAnswer(inv -> {
                    Files.copy(inv.getArgument(4, InputStream.class), target);
                    return target;
                });
    }

    @Test
    void export_isByteExact_forBinaryContent(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("train.parquet");
        writeStreamCopiesToTempFile(target);

        Map<String, Object> out = tool.invoke(params(), CTX);

        assertThat(Files.readAllBytes(target)).isEqualTo(BINARY);
        assertThat(out).containsEntry("bytes", (long) BINARY.length);
        assertThat(out).doesNotContainKey("chars");
        assertThat(out).containsEntry("absolutePath", target.toString());
        assertThat(out).containsEntry("workspacePath", "train.parquet");
        assertThat(out).containsEntry("dirName", "ws");
    }

    @Test
    void export_flushesTheBufferBeforeReadingBytes(@TempDir Path tmp) throws Exception {
        writeStreamCopiesToTempFile(tmp.resolve("train.parquet"));

        tool.invoke(params(), CTX);

        verify(buffer).flush(CTX.processId(), "doc-1");
        verify(documentService).loadContent(doc);
    }

    @Test
    void export_neverTouchesTheTextBodyPath(@TempDir Path tmp) throws Exception {
        // readBody() is the corruption path — a binary doc must never go through it.
        writeStreamCopiesToTempFile(tmp.resolve("train.parquet"));

        tool.invoke(params(), CTX);

        verify(support, never()).readBody(any(), any());
        verify(workspace).writeStream(eq("acme"), eq("proj-a"), eq("ws"), anyString(), any());
    }

    @Test
    void vanishedDocument_failsWithToolException() {
        when(buffer.read(CTX.processId(), "doc-1")).thenReturn(null);

        assertThatThrownBy(() -> tool.invoke(params(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessage("Source document disappeared during export");
    }

    @Test
    void workspaceFailure_isWrappedAsToolException(@TempDir Path tmp) {
        when(workspace.writeStream(any(), any(), any(), any(), any())).thenThrow(new WorkspaceExceptionWriteStub());

        assertThatThrownBy(() -> tool.invoke(params(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageStartingWith("Failed to write workspace file:");
    }

    /** Minimal RuntimeException stand-in for a WorkspaceException (the tool only sees RuntimeException). */
    private static final class WorkspaceExceptionWriteStub extends RuntimeException {
        WorkspaceExceptionWriteStub() {
            super("disk full");
        }
    }
}
