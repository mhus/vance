package de.mhus.vance.brain.rag;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.rag.RagCatalogService;
import de.mhus.vance.shared.rag.RagDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Brain-side façade for the project-default RAG ({@link RagCatalogService#DEFAULT_RAG_NAME}).
 *
 * <p>Owns the lifecycle binding (create on bring, drop on close) and the
 * reindex/rebuild paths driven from the admin REST and the web UI. The
 * actual document → chunks pipeline lives in {@link ProjectRagIndexer}; this
 * service decides <em>when</em> to bring the RAG itself into existence,
 * mark documents dirty for re-indexing, and resolve the cascade settings
 * that gate everything.
 *
 * <p>See {@code planning/project-rag.md} §4.1, §4.5 and §5.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectRagService {

    /** Cascade setting that toggles the whole project-RAG subsystem off. */
    public static final String SETTING_PROJECT_RAG_ENABLED = "rag.project.enabled";

    /** Cascade setting that toggles inclusion of document summaries as chunks. */
    public static final String SETTING_INCLUDE_SUMMARIES = "rag.project.includeSummaries";

    /** Pebble-template default for the default RAG's chunk size (chars). */
    private static final int DEFAULT_CHUNK_SIZE = 1500;
    /** Default chunk overlap (chars). */
    private static final int DEFAULT_CHUNK_OVERLAP = 200;

    private final RagService ragService;
    private final RagCatalogService catalog;
    private final DocumentService documentService;
    private final SettingService settingService;

    /**
     * Idempotent: returns the existing {@code _documents}-RAG of the
     * project or creates a fresh one with the tenant's currently
     * configured embedding settings. Called from
     * {@code ProjectLifecycleService.bring()}.
     */
    public RagDocument ensureDefaultRag(String tenantId, String projectId) {
        Optional<RagDocument> existing = catalog.findByName(
                tenantId, projectId, RagCatalogService.DEFAULT_RAG_NAME);
        if (existing.isPresent()) return existing.get();
        RagDocument fresh = ragService.createRag(
                tenantId, projectId,
                RagCatalogService.DEFAULT_RAG_NAME,
                "Project documents",
                "Auto-indexed text documents under documents/.",
                DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        log.info("Project-RAG ensured tenant='{}' project='{}' id='{}'",
                tenantId, projectId, fresh.getId());
        return fresh;
    }

    /**
     * Drops the project-default RAG and any chunks. Called on
     * {@code ProjectLifecycleService.close()}. Safe to call on a
     * never-claimed project.
     */
    public void disposeDefaultRag(String tenantId, String projectId) {
        Optional<RagDocument> existing = catalog.findByName(
                tenantId, projectId, RagCatalogService.DEFAULT_RAG_NAME);
        if (existing.isEmpty()) return;
        catalog.delete(existing.get().getId());
        log.info("Project-RAG disposed tenant='{}' project='{}'", tenantId, projectId);
    }

    /** Project-RAG lookup — returns empty if the project never had one. */
    public Optional<RagDocument> findDefaultRag(String tenantId, String projectId) {
        return catalog.findByName(tenantId, projectId, RagCatalogService.DEFAULT_RAG_NAME);
    }

    /**
     * Re-index the project's documents into the {@code _documents}-RAG.
     *
     * <p>{@code rebuild=false}: marks every ACTIVE document dirty; the
     * scheduler picks them up over the next ticks. RAG identity and
     * embedding model stay the same.
     *
     * <p>{@code rebuild=true}: drops the {@code _documents}-RAG (including
     * all chunks), re-creates it with the tenant's <em>current</em>
     * embedding settings, then marks every ACTIVE document dirty.
     * The path for "switch to a different embedding provider".
     *
     * @return number of documents queued for re-indexing.
     */
    public long reindex(String tenantId, String projectId, boolean rebuild) {
        if (rebuild) {
            disposeDefaultRag(tenantId, projectId);
            ensureDefaultRag(tenantId, projectId);
        } else {
            ensureDefaultRag(tenantId, projectId);
        }
        long queued = documentService.markAllForReindex(tenantId, projectId);
        log.info("Project-RAG reindex queued tenant='{}' project='{}' docs={} rebuild={}",
                tenantId, projectId, queued, rebuild);
        return queued;
    }

    /** Cascade-resolved master toggle — default {@code true}. */
    public boolean isEnabled(String tenantId, String projectId) {
        return settingService.getBooleanValueCascade(
                tenantId, projectId, /*thinkProcessId*/ null,
                SETTING_PROJECT_RAG_ENABLED, /*default*/ true);
    }

    /** Cascade-resolved summary-inclusion toggle — default {@code true}. */
    public boolean includeSummaries(String tenantId, String projectId) {
        return settingService.getBooleanValueCascade(
                tenantId, projectId, /*thinkProcessId*/ null,
                SETTING_INCLUDE_SUMMARIES, /*default*/ true);
    }
}
