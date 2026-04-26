package de.mhus.vance.shared.rag;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Catalog-side lifecycle for {@link RagDocument}: create, look up,
 * list per project, delete. Chunk-side operations (add/search/delete
 * chunks) go through the {@link RagBackend} — orchestration that
 * spans both (e.g. embed + add, embed + search) is the brain-side
 * {@code RagService}'s job.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagCatalogService {

    private final RagRepository repository;
    private final RagBackend backend;

    /**
     * Creates a new RAG. Throws {@link RagAlreadyExistsException} if a
     * RAG with the same {@code name} already exists in the project.
     */
    public RagDocument create(
            String tenantId,
            String projectId,
            String name,
            @Nullable String title,
            @Nullable String description,
            String embeddingProvider,
            String embeddingModel,
            int embeddingDim,
            int chunkSize,
            int chunkOverlap) {
        if (repository.existsByTenantIdAndProjectIdAndName(tenantId, projectId, name)) {
            throw new RagAlreadyExistsException(
                    "RAG '" + name + "' already exists in project '"
                            + projectId + "' (tenant '" + tenantId + "')");
        }
        RagDocument doc = RagDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .name(name)
                .title(title)
                .description(description)
                .embeddingProvider(embeddingProvider)
                .embeddingModel(embeddingModel)
                .embeddingDim(embeddingDim)
                .chunkSize(Math.max(100, chunkSize))
                .chunkOverlap(Math.max(0, Math.min(chunkOverlap, chunkSize - 1)))
                .chunkCount(0)
                .build();
        RagDocument saved = repository.save(doc);
        log.info("Created RAG tenant='{}' project='{}' name='{}' model='{}/{}' dim={} id='{}'",
                tenantId, projectId, name, embeddingProvider, embeddingModel,
                embeddingDim, saved.getId());
        return saved;
    }

    public Optional<RagDocument> findById(String id) {
        return repository.findById(id);
    }

    public Optional<RagDocument> findByName(String tenantId, String projectId, String name) {
        return repository.findByTenantIdAndProjectIdAndName(tenantId, projectId, name);
    }

    public List<RagDocument> listByProject(String tenantId, String projectId) {
        return repository.findByTenantIdAndProjectId(tenantId, projectId);
    }

    /**
     * Refreshes the {@code chunkCount} field from the actual backend
     * count and persists. Cheap denormalisation — the truth is the
     * backend; this is for UI/list views.
     */
    public RagDocument refreshChunkCount(RagDocument rag) {
        long count = backend.count(rag.getTenantId(), rag.getId());
        if (rag.getChunkCount() == count) return rag;
        rag.setChunkCount(count);
        return repository.save(rag);
    }

    /**
     * Deletes the RAG: drops every chunk through the backend, then
     * removes the catalog entry. Safe to call on a missing id.
     */
    public boolean delete(String id) {
        Optional<RagDocument> opt = repository.findById(id);
        if (opt.isEmpty()) return false;
        RagDocument rag = opt.get();
        long chunks = backend.deleteByRag(rag.getTenantId(), rag.getId());
        repository.deleteById(id);
        log.info("Deleted RAG tenant='{}' name='{}' chunks={} id='{}'",
                rag.getTenantId(), rag.getName(), chunks, id);
        return true;
    }

    public static class RagAlreadyExistsException extends RuntimeException {
        public RagAlreadyExistsException(String message) {
            super(message);
        }
    }
}
