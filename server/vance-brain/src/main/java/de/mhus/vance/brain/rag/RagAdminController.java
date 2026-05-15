package de.mhus.vance.brain.rag;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.rag.RagCatalogService;
import de.mhus.vance.shared.rag.RagDocument;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * </ul>
 *
 * <p>Both endpoints require {@link Action#WRITE} on the project resource —
 * read-only status is intentionally on the same gate as the reindex because
 * the data (chunk-count, model identity) belongs to project-internal
 * configuration, not public metadata.
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

    private final ProjectRagService projectRagService;
    private final RagCatalogService ragCatalog;
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
        Optional<RagDocument> opt = projectRagService.findDefaultRag(tenant, project);
        if (opt.isEmpty()) {
            return new StatusResponse(false, null, null, null, 0L, null);
        }
        RagDocument rag = ragCatalog.refreshChunkCount(opt.get());
        return new StatusResponse(
                true,
                rag.getId(),
                rag.getEmbeddingProvider(),
                rag.getEmbeddingModel(),
                rag.getChunkCount(),
                rag.getCreatedAt());
    }

    public record ReindexResponse(boolean rebuild, long documentsQueued) {}

    public record StatusResponse(
            boolean exists,
            @Nullable String ragId,
            @Nullable String embeddingProvider,
            @Nullable String embeddingModel,
            long chunkCount,
            @Nullable Instant createdAt) {}
}
