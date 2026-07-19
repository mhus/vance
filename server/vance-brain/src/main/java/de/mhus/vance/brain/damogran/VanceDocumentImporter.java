package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Imports a project document ({@code vance:<path>}) into the workspace. The
 * destination write goes through {@code ctx.fileIo()}, so it lands in the
 * server RootDir (WORK) or on the remote host (CLIENT/DAEMON) transparently.
 */
@Component
class VanceDocumentImporter implements DamogranImporter {

    private final DocumentService documentService;

    VanceDocumentImporter(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public Set<String> schemes() {
        return Set.of("vance");
    }

    @Override
    public void doImport(DamogranContext ctx, ImportEntry entry) {
        DamogranUri.VanceRef ref = DamogranUri.resolveVance(ctx.composeBaseDir(), entry.from());
        String project = ref.project() != null ? ref.project() : ctx.projectId();
        // Cross-project ACL is enforced by findByPath.
        Optional<DocumentDocument> doc =
                documentService.findByPath(ctx.tenantId(), project, ref.path());
        if (doc.isEmpty()) {
            throw new DamogranException("import source not found: " + entry.from());
        }
        try (InputStream in = documentService.loadContent(doc.get())) {
            ctx.requireFileIo("import").writeBytes(entry.to(), in.readAllBytes());
        } catch (IOException e) {
            throw new DamogranException("import failed reading " + entry.from() + ": " + e.getMessage(), e);
        }
    }
}
