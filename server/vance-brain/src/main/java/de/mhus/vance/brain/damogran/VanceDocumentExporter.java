package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Exports a workspace file to a project document ({@code vance:<path>}).
 * Textual mime → editable text document; otherwise a binary document.
 */
@Component
class VanceDocumentExporter implements DamogranExporter {

    /** Hard ceiling on a single exported file (50 MiB). */
    private static final long MAX_EXPORT_BYTES = 50L * 1024 * 1024;
    private static final String CREATED_BY = "damogran";

    private final DocumentService documentService;
    private final WorkspaceService workspaceService;

    VanceDocumentExporter(DocumentService documentService, WorkspaceService workspaceService) {
        this.documentService = documentService;
        this.workspaceService = workspaceService;
    }

    @Override
    public Set<String> schemes() {
        return Set.of("vance");
    }

    @Override
    public void doExport(DamogranContext ctx, ExportEntry entry) {
        DamogranWorkspaceIo.requireWorkRoot(ctx, "export");
        String docPath = DamogranUri.stripVance(entry.to());
        byte[] bytes = DamogranWorkspaceIo.readBytes(workspaceService, ctx, entry.from(), MAX_EXPORT_BYTES);
        String mime = DamogranMime.mimeForPath(docPath);

        if (DamogranMime.isText(mime)) {
            documentService.upsertText(ctx.tenantId(), ctx.projectId(), docPath,
                    null, null, new String(bytes, StandardCharsets.UTF_8), CREATED_BY);
        } else {
            documentService.createOrReplaceBinary(ctx.tenantId(), ctx.projectId(), docPath,
                    bytes, mime, null, null, null, CREATED_BY);
        }
    }
}
