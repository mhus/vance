package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.shared.document.DocumentService;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Exports a workspace file to a project document ({@code vance:<path>}). The
 * source read goes through {@code ctx.fileIo()} (server RootDir or remote host).
 * Textual mime → editable text document; otherwise a binary document.
 */
@Component
class VanceDocumentExporter implements DamogranExporter {

    /** Hard ceiling on a single exported file (50 MiB). */
    private static final long MAX_EXPORT_BYTES = 50L * 1024 * 1024;
    private static final String CREATED_BY = "damogran";

    private final DocumentService documentService;

    VanceDocumentExporter(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public Set<String> schemes() {
        return Set.of("vance");
    }

    @Override
    public void doExport(DamogranContext ctx, ExportEntry entry) {
        DamogranUri.VanceRef ref = DamogranUri.resolveVance(ctx.composeBaseDir(), entry.to());
        String project = ref.project() != null ? ref.project() : ctx.projectId();
        String docPath = ref.path();
        byte[] bytes = ctx.requireFileIo("export").readBytes(entry.from(), MAX_EXPORT_BYTES);
        String mime = DamogranMime.mimeForPath(docPath);

        if (DamogranMime.isText(mime)) {
            documentService.upsertText(ctx.tenantId(), project, docPath,
                    null, null, new String(bytes, StandardCharsets.UTF_8), CREATED_BY,
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        } else {
            documentService.createOrReplaceBinary(ctx.tenantId(), project, docPath,
                    bytes, mime, null, null, null, CREATED_BY,
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        }
    }
}
