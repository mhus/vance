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
    private final de.mhus.vance.shared.permission.PermissionService permissionService;

    VanceDocumentImporter(DocumentService documentService,
            de.mhus.vance.shared.permission.PermissionService permissionService) {
        this.documentService = documentService;
        this.permissionService = permissionService;
    }

    @Override
    public Set<String> schemes() {
        return Set.of("vance");
    }

    @Override
    public void doImport(DamogranContext ctx, ImportEntry entry) {
        DamogranUri.VanceRef ref = DamogranUri.resolveVance(ctx.composeBaseDir(), entry.from());
        String project = ref.project() != null ? ref.project() : ctx.projectId();
        // findByPath is a raw repository lookup with NO authorization — a manifest
        // may name any project (vance://otherProject/…), so authorize the READ
        // against the run's caller (the user, directly or via the agent session)
        // before reading. The provider decides: cross-project is allowed only if
        // the caller has READ there. A null caller is an internal system run.
        if (ctx.caller() != null) {
            permissionService.enforce(ctx.caller(),
                    new de.mhus.vance.shared.permission.Resource.Document(
                            ctx.tenantId(), project, ref.path()),
                    de.mhus.vance.shared.permission.Action.READ);
        }
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
