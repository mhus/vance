package de.mhus.vance.brain.rag;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.rag.RagBackend.SearchHit;
import de.mhus.vance.shared.rag.RagCatalogService;
import de.mhus.vance.shared.rag.RagDocument;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST surface for the project-default RAG ({@code _documents}).
 *
 * <ul>
 *   <li>{@code POST /brain/{tenant}/projects/{name}/rag/reindex?rebuild=bool}
 *       — drops the chunk-set (or rebuilds the RAG itself if {@code rebuild=true})
 *       and queues every ACTIVE document for re-indexing. The scheduler does
 *       the actual work.</li>
 *   <li>{@code GET /brain/{tenant}/projects/{name}/rag/status} — read-only
 *       snapshot for the web-UI status widget.</li>
 *   <li>{@code POST /brain/{tenant}/projects/{name}/rag/search} — similarity
 *       search against the project-default RAG. Body: {@code {"query": "..."}}.
 *       Returns up to {@link #SEARCH_MAX_RESULTS} hits.</li>
 * </ul>
 *
 * <p>All endpoints require {@link Action#WRITE} on the project resource —
 * read-only status and search are intentionally on the same gate as reindex
 * because the data (chunk-count, model identity, raw chunk text) belongs to
 * project-internal configuration, not public metadata.
 *
 * <p>Tenant in the path is validated by
 * {@code de.mhus.vance.brain.access.BrainAccessFilter} against the JWT's
 * {@code tid} claim before requests reach this controller.
 */
@RestController
@RequestMapping("/brain/{tenant}/projects/{project}/rag")
@RequiredArgsConstructor
@Slf4j
public class RagAdminController {

    /** Hard cap on the search REST surface — UI is allowed to ask for up to this many hits. */
    private static final int SEARCH_MAX_RESULTS = 20;

    private final ProjectRagService projectRagService;
    private final RagCatalogService ragCatalog;
    private final RagService ragService;
    private final RequestAuthority authority;

    @PostMapping("/reindex")
    public ReindexResponse reindex(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam(name = "rebuild", defaultValue = "false") boolean rebuild,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.WRITE);
        try {
            long queued = projectRagService.reindex(tenant, project, rebuild);
            return new ReindexResponse(rebuild, queued);
        } catch (RuntimeException e) {
            log.warn("RAG reindex failed tenant='{}' project='{}' rebuild={}: {}",
                    tenant, project, rebuild, e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/status")
    public StatusResponse status(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.WRITE);
        String effectiveProvider = ragService.effectiveProvider(tenant, project);
        boolean enabled = ragService.isEmbeddingEnabled(tenant, project);
        Optional<RagDocument> opt = projectRagService.findDefaultRag(tenant, project);
        if (opt.isEmpty()) {
            return new StatusResponse(false, null, null, null, 0L, null,
                    effectiveProvider, enabled);
        }
        RagDocument rag = ragCatalog.refreshChunkCount(opt.get());
        return new StatusResponse(
                true,
                rag.getId(),
                rag.getEmbeddingProvider(),
                rag.getEmbeddingModel(),
                rag.getChunkCount(),
                rag.getCreatedAt(),
                effectiveProvider,
                enabled);
    }

    /**
     * Similarity-search the project-default RAG. Returns up to
     * {@link #SEARCH_MAX_RESULTS} hits ordered by descending similarity.
     * Empty hits list when the RAG does not exist yet or the query was
     * blank — never a 404, so the UI can keep the panel quiet until the
     * user actually issues a query.
     */
    @PostMapping("/search")
    public SearchResponse search(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestBody SearchRequest request,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.WRITE);
        String query = request == null ? null : request.query();
        if (query == null || query.isBlank()) {
            return new SearchResponse(List.of());
        }
        Optional<RagDocument> opt = projectRagService.findDefaultRag(tenant, project);
        if (opt.isEmpty()) {
            return new SearchResponse(List.of());
        }
        try {
            List<SearchHit> hits = ragService.query(opt.get().getId(), query, SEARCH_MAX_RESULTS);
            List<SearchHitDto> out = hits.stream()
                    .map(h -> new SearchHitDto(
                            h.chunk().getSourceRef(),
                            h.chunk().getPosition(),
                            h.chunk().getContent(),
                            h.score()))
                    .toList();
            return new SearchResponse(out);
        } catch (RuntimeException e) {
            log.warn("RAG search failed tenant='{}' project='{}': {}",
                    tenant, project, e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    public record ReindexResponse(boolean rebuild, long documentsQueued) {}

    public record SearchRequest(@Nullable String query) {}

    public record SearchResponse(List<SearchHitDto> hits) {}

    public record SearchHitDto(
            @Nullable String sourceRef,
            int position,
            String content,
            double score) {}

    /**
     * @param exists              whether a {@code _documents}-RAG row currently
     *                            exists for the project.
     * @param ragId               Mongo id of the RAG (when {@code exists}).
     * @param embeddingProvider   provider name baked into the RAG at create
     *                            time (when {@code exists}). May differ from
     *                            {@code effectiveProvider} if the tenant
     *                            switched providers after the RAG was
     *                            created — UI hints at the mismatch.
     * @param embeddingModel      model name baked into the RAG at create time.
     * @param chunkCount          number of indexed chunks (when {@code exists}).
     * @param createdAt           when the RAG was created (when {@code exists}).
     * @param effectiveProvider   cascade-resolved current
     *                            {@code ai.embedding.provider} for the
     *                            {@code (tenant, project)} scope. Always
     *                            present; defaults to {@code none}.
     * @param enabled             convenience flag: {@code effectiveProvider}
     *                            is something other than {@code none}.
     *                            UI uses this to hide search / actions when
     *                            embedding is off.
     */
    public record StatusResponse(
            boolean exists,
            @Nullable String ragId,
            @Nullable String embeddingProvider,
            @Nullable String embeddingModel,
            long chunkCount,
            @Nullable Instant createdAt,
            String effectiveProvider,
            boolean enabled) {}
}
