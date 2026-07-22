package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.tools.client.CortexTurnSelectionHolder;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests the write-back decision of {@code compose_block_run}: on success the
 * produced artifacts land in the block's {@code $output:}; a failure (or a
 * user-authored {@code output:} override) clears the managed block instead.
 * The codec's byte-exact output is covered separately by ComposeBlockCodecTest.
 */
class ComposeBlockRunToolTest {

    private DamogranComposeService composeService;
    private DocumentService documentService;
    private KindToolSupport support;
    private CortexTurnSelectionHolder selectionHolder;
    private ComposeBlockRunTool tool;
    private DocumentDocument doc;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setup() {
        composeService = mock(DamogranComposeService.class);
        documentService = mock(DocumentService.class);
        support = mock(KindToolSupport.class);
        selectionHolder = mock(CortexTurnSelectionHolder.class);
        ComposeFinishedNotifier notifier = mock(ComposeFinishedNotifier.class);
        de.mhus.vance.brain.ws.signals.SignalBroadcaster signals =
                mock(de.mhus.vance.brain.ws.signals.SignalBroadcaster.class);
        tool = new ComposeBlockRunTool(composeService, documentService, support, notifier, selectionHolder, signals);

        doc = mock(DocumentDocument.class);
        ctx = mock(ToolInvocationContext.class);
        when(doc.getId()).thenReturn("doc-1");
        when(doc.getPath()).thenReturn("notes/x.compose.yaml");
        when(ctx.tenantId()).thenReturn("t");
        when(ctx.resolveLocalProjectId()).thenReturn("p");
        when(ctx.processId()).thenReturn("proc-1");
        when(support.loadDocument(any(), any())).thenReturn(doc);
        when(support.readBody(doc, ctx)).thenReturn("name: demo\n");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
    }

    private ComposeRun terminalRun(DamogranComposeResult result) {
        ComposeRun run = mock(ComposeRun.class);
        try {
            when(run.awaitDone(anyLong())).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(run.isTerminal()).thenReturn(true);
        when(run.result()).thenReturn(result);
        // Disambiguate the (…, String yaml, …) overload from the manifest one.
        when(composeService.runAsync(anyString(), anyString(), any(), anyString(), any())).thenReturn(run);
        return run;
    }

    @Test
    void fastPathSuccess_writesOutputBlockIntoDoc() {
        DamogranComposeResult result = new DamogranComposeResult(
                DamogranStatus.SUCCESS, "ws",
                List.of(DamogranTaskResult.success(List.of(new OutputArtifact("out/a.txt", null, null, null)))),
                null);
        terminalRun(result);
        when(documentService.readContent(doc)).thenReturn("name: demo\n");

        Map<String, Object> out = tool.invoke(Map.of(), ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        assertThat(written.getValue()).isEqualTo(
                "name: demo\n\n# generated — compose run state (do not edit)\n"
                        + "$output:\n  - path: out/a.txt\n    uri: vance-workspace:/ws/out/a.txt\n");
        assertThat(out).containsEntry("success", true);
    }

    @Test
    void fastPathFailure_clearsManagedBlock() {
        DamogranComposeResult result = new DamogranComposeResult(
                DamogranStatus.FAILURE, "ws", List.of(DamogranTaskResult.failure("boom")), "boom");
        terminalRun(result);
        // Doc still carries a stale $output from a prior run → failure clears it.
        when(documentService.readContent(doc)).thenReturn(
                "name: demo\n\n# generated — compose run state (do not edit)\n$output:\n  - path: old\n    uri: u\n");

        tool.invoke(Map.of(), ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        assertThat(written.getValue()).isEqualTo("name: demo\n");
    }

    @Test
    void fastPathSuccess_withFixedOutputOverride_clearsInsteadOfWriting() {
        DamogranComposeResult result = new DamogranComposeResult(
                DamogranStatus.SUCCESS, "ws",
                List.of(DamogranTaskResult.success(List.of(new OutputArtifact("out/a.txt", null, null, null)))),
                null);
        terminalRun(result);
        // User pinned a fixed `output:` list + a stale $run marker → the run must
        // clear the managed block (drop $run) and NOT write a $output list.
        when(documentService.readContent(doc)).thenReturn(
                "name: demo\noutput:\n  - path: fixed.txt\n    uri: vance-workspace:/ws/fixed.txt\n\n"
                        + "# generated — compose run state (do not edit)\n$run:\n  id: cr-old\n");

        tool.invoke(Map.of(), ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        assertThat(written.getValue()).doesNotContain("$output:");
        assertThat(written.getValue()).doesNotContain("$run:");
        assertThat(written.getValue()).contains("output:\n  - path: fixed.txt");
    }

    @Test
    void fastPathFailure_noPriorBlock_isNoOpWrite() {
        DamogranComposeResult result = new DamogranComposeResult(
                DamogranStatus.FAILURE, "ws", List.of(DamogranTaskResult.failure("boom")), "boom");
        terminalRun(result);
        when(documentService.readContent(doc)).thenReturn("name: demo\n");

        tool.invoke(Map.of(), ctx);

        verify(support, never()).writeBody(any(), any(), any());
    }

    // ──────────────────── inline workpage fences ────────────────────

    private static final String TWO_FENCES =
            "# Page\n\n```vance-compose\nname: first\n```\n\n```vance-compose\nname: second\n```\n";

    private DamogranComposeResult successWith(String path) {
        return new DamogranComposeResult(
                DamogranStatus.SUCCESS, "ws",
                List.of(DamogranTaskResult.success(List.of(new OutputArtifact(path, null, null, null)))),
                null);
    }

    @Test
    void inlineSelectedFence_splicesOutputIntoThatBlockOnly() {
        terminalRun(successWith("out/a.txt"));
        when(support.readBody(doc, ctx)).thenReturn(TWO_FENCES);
        when(documentService.readContent(doc)).thenReturn(TWO_FENCES);
        // Selection sits inside the SECOND fence.
        when(selectionHolder.get("proc-1")).thenReturn(new CortexTurnSelectionHolder.Selection(
                "doc-1", TWO_FENCES.indexOf("name: second"), TWO_FENCES.indexOf("name: second") + 4));

        tool.invoke(Map.of(), ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        String out = written.getValue();
        // Second fence got the $output; first fence is untouched.
        assertThat(out).contains("name: second\n\n# generated — compose run state (do not edit)\n"
                + "$output:\n  - path: out/a.txt\n    uri: vance-workspace:/ws/out/a.txt\n");
        assertThat(out).contains("```vance-compose\nname: first\n```");
    }

    @Test
    void inlineSingleFence_noSelection_usesTheOnlyBlock() {
        String oneFence = "# Page\n\n```vance-compose\nname: only\n```\n";
        terminalRun(successWith("r.md"));
        when(support.readBody(doc, ctx)).thenReturn(oneFence);
        when(documentService.readContent(doc)).thenReturn(oneFence);
        when(selectionHolder.get("proc-1")).thenReturn(null);

        tool.invoke(Map.of(), ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        assertThat(written.getValue()).contains("name: only\n\n# generated");
    }

    @Test
    void inlineMultipleFences_noSelection_errors() {
        when(support.readBody(doc, ctx)).thenReturn(TWO_FENCES);
        when(selectionHolder.get("proc-1")).thenReturn(null);

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("multiple compose blocks");
    }
}
