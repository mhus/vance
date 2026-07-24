package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.permission.WriteReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Security regression (code-review-2 damogran HIGH): {@code vance:} import/export
 * addressed cross-project with zero authorization, and exports wrote as
 * WriteActor.SYSTEM (fail-open). Both now honor the run's caller.
 */
class VanceDocumentTransportAuthzTest {

    private static final SecurityContext ALICE =
            SecurityContext.user("alice", "acme", List.of());

    private DamogranContext ctx(SecurityContext caller, ComposeFileIo io) {
        return new DamogranContext("acme", "projA", null, "ws", "ws", null,
                "WORK", null, null, io, null, null, null, caller);
    }

    // ── Exporter: user actor, never SYSTEM ──────────────────────────

    @Test
    void export_writesWithUserActor_carryingCaller_notSystem() {
        DocumentService docs = mock(DocumentService.class);
        ComposeFileIo io = mock(ComposeFileIo.class);
        when(io.readBytes(any(), anyLong())).thenReturn("hi".getBytes());
        VanceDocumentExporter exporter = new VanceDocumentExporter(docs);

        exporter.doExport(ctx(ALICE, io), new ExportEntry("out.md", "vance:/result.md", Map.of()));

        ArgumentCaptor<WriteActor> actor = ArgumentCaptor.forClass(WriteActor.class);
        verify(docs).upsertText(eq("acme"), eq("projA"), eq("result.md"),
                any(), any(), any(), any(), actor.capture());
        assertThat(actor.getValue().reason()).isEqualTo(WriteReason.USER);
        assertThat(actor.getValue().subject()).isEqualTo(ALICE);
    }

    @Test
    void export_nullCaller_writesAsSystem() {
        DocumentService docs = mock(DocumentService.class);
        ComposeFileIo io = mock(ComposeFileIo.class);
        when(io.readBytes(any(), anyLong())).thenReturn("hi".getBytes());
        VanceDocumentExporter exporter = new VanceDocumentExporter(docs);

        exporter.doExport(ctx(null, io), new ExportEntry("out.md", "vance:/result.md", Map.of()));

        ArgumentCaptor<WriteActor> actor = ArgumentCaptor.forClass(WriteActor.class);
        verify(docs).upsertText(any(), any(), any(), any(), any(), any(), any(), actor.capture());
        assertThat(actor.getValue().reason()).isEqualTo(WriteReason.SYSTEM);
    }

    // ── Importer: enforce READ against the caller for cross-project ──

    @Test
    void import_crossProject_enforcesReadForCaller_beforeReading() {
        DocumentService docs = mock(DocumentService.class);
        PermissionService perms = mock(PermissionService.class);
        // enforce throws on deny → the import must not read the foreign doc.
        doThrow(new RuntimeException("denied")).when(perms).enforce(
                eq(ALICE),
                eq(new Resource.Document("acme", "projB", "secret.md")),
                eq(Action.READ));
        VanceDocumentImporter importer = new VanceDocumentImporter(docs, perms);

        assertThatThrownBy(() -> importer.doImport(
                ctx(ALICE, mock(ComposeFileIo.class)),
                new ImportEntry("vance://projB/secret.md", "local.md", Map.of())))
                .isInstanceOf(RuntimeException.class);

        verify(perms).enforce(eq(ALICE),
                eq(new Resource.Document("acme", "projB", "secret.md")), eq(Action.READ));
        verify(docs, never()).findByPath(any(), eq("projB"), any());
    }
}
