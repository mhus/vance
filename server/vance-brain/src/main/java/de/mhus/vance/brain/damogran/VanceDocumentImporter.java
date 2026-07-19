package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Imports a project document ({@code vance:<path>}) into the workspace.
 */
@Component
class VanceDocumentImporter implements DamogranImporter {

    private final DocumentService documentService;
    private final WorkspaceService workspaceService;

    VanceDocumentImporter(DocumentService documentService, WorkspaceService workspaceService) {
        this.documentService = documentService;
        this.workspaceService = workspaceService;
    }

    @Override
    public Set<String> schemes() {
        return Set.of("vance");
    }

    @Override
    public void doImport(DamogranContext ctx, ImportEntry entry) {
        DamogranWorkspaceIo.requireWorkRoot(ctx, "import");
        String path = DamogranUri.stripVance(entry.from());
        Optional<DocumentDocument> doc =
                documentService.findByPath(ctx.tenantId(), ctx.projectId(), path);
        if (doc.isEmpty()) {
            throw new DamogranException("import source not found: " + entry.from());
        }
        try (InputStream in = documentService.loadContent(doc.get())) {
            DamogranWorkspaceIo.writeBytes(workspaceService, ctx, entry.to(), in.readAllBytes());
        } catch (IOException e) {
            throw new DamogranException("import failed reading " + entry.from() + ": " + e.getMessage(), e);
        }
    }
}
