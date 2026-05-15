package de.mhus.vance.brain.rag;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.rag.RagDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Per-document chunk pipeline for the project-default RAG. Invoked by the
 * {@link ProjectRagIndexScheduler} once per claimed document.
 *
 * <p>Pipeline (see {@code planning/project-rag.md} §4.4):
 * <ol>
 *   <li>Re-check eligibility (filter may have changed between dirty-set and claim).</li>
 *   <li>Drop existing chunks for the document via {@code removeBySource}.</li>
 *   <li>If eligible: chunk + embed + insert the content. If the cascade
 *       setting {@code rag.project.includeSummaries} is on and the
 *       document carries a summary, insert a second {@code kind=summary}
 *       chunk for the same source ref.</li>
 *   <li>Clear {@code ragDirty} via {@link DocumentService#markRagClean}.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectRagIndexer {

    private final ProjectRagService projectRagService;
    private final RagService ragService;
    private final DocumentService documentService;

    /**
     * Re-indexes a single document. Throws on transient failures
     * (Embed-Provider down, etc.) so the scheduler can leave the claim
     * untouched and let the TTL release it for retry.
     */
    public void reindexDocument(DocumentDocument doc) {
        RagDocument defaultRag = projectRagService.ensureDefaultRag(
                doc.getTenantId(), doc.getProjectId());

        // Always purge old chunks first — idempotent across re-runs and the
        // single op that covers the "filter changed to exclude" case.
        ragService.removeBySource(defaultRag.getId(), doc.getId());

        boolean eligible = documentService.isRagEligible(doc);
        if (!eligible) {
            documentService.markRagClean(doc.getId());
            log.debug("RAG purge tenant='{}' project='{}' doc='{}' (filter rejected)",
                    doc.getTenantId(), doc.getProjectId(), doc.getPath());
            return;
        }

        String content = doc.getInlineText();
        if (content == null || content.isBlank()) {
            // Storage-backed text? v1 only indexes inline. A later PR can
            // stream from StorageService when needed.
            documentService.markRagClean(doc.getId());
            log.debug("RAG skip tenant='{}' project='{}' doc='{}' (no inline content)",
                    doc.getTenantId(), doc.getProjectId(), doc.getPath());
            return;
        }

        Map<String, Object> contentMeta = new LinkedHashMap<>();
        contentMeta.put("kind", "content");
        contentMeta.put("path", doc.getPath());
        ragService.addText(defaultRag.getId(), doc.getId(), content, contentMeta);

        if (projectRagService.includeSummaries(doc.getTenantId(), doc.getProjectId())
                && doc.getSummary() != null && !doc.getSummary().isBlank()) {
            Map<String, Object> summaryMeta = new LinkedHashMap<>();
            summaryMeta.put("kind", "summary");
            summaryMeta.put("path", doc.getPath());
            ragService.addText(defaultRag.getId(), doc.getId(),
                    doc.getSummary(), summaryMeta);
        }

        documentService.markRagClean(doc.getId());
    }
}
