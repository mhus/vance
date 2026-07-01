package de.mhus.vance.brain.tools.client;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the Cortex "bound file" id (which travels per-turn with the
 * steer — {@code ProcessSteerRequest.boundDocumentId}) to its document
 * <em>path</em> for prompt injection.
 *
 * <p>Only the path is surfaced, not the content: the agent already has
 * {@code doc_read} and reads the file on demand. Inlining the whole
 * document every turn would bloat the prompt for no gain — the prompt
 * just needs to tell the model <em>which</em> file "this file" refers to.
 *
 * <p>Scope is enforced — the document must belong to the caller's
 * tenant/project. Returns {@code null} when nothing is bound or the id
 * doesn't resolve.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CortexBoundDocumentResolver {

    private final DocumentService documentService;

    public @Nullable String resolvePath(
            @Nullable String documentId,
            @Nullable String tenantId,
            @Nullable String projectId) {
        if (documentId == null || documentId.isBlank()) return null;
        var docOpt = documentService.findById(documentId);
        if (docOpt.isEmpty()) {
            log.trace("bound-document not found by id='{}'", documentId);
            return null;
        }
        DocumentDocument doc = docOpt.get();
        if (!Objects.equals(doc.getTenantId(), tenantId)
                || !Objects.equals(doc.getProjectId(), projectId)) {
            log.warn("bound-document scope mismatch: docId='{}' doc='{}/{}' caller='{}/{}'",
                    documentId, doc.getTenantId(), doc.getProjectId(), tenantId, projectId);
            return null;
        }
        return doc.getPath() != null ? doc.getPath() : doc.getName();
    }
}
