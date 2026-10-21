package de.mhus.vance.brain.cleanup;

import de.mhus.vance.api.cleanup.StorageBreakdownDto;
import de.mhus.vance.api.cleanup.StorageCleanupRequest;
import de.mhus.vance.api.cleanup.StorageCleanupResultDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.cleanup.StorageCleanupService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.WriteActor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST surface for the Insights → Quotas storage-cleanup actions.
 *
 * <p>Three endpoints, all tenant-scoped, all admin-only:
 * <ul>
 *   <li>{@code GET /brain/{tenant}/storage/breakdown?projectId=X} — the
 *       bytes-of-storage breakdown that drives the Quotas tab's "Live /
 *       Trash / Archives" rows. Cheap: three {@code $group} sums plus
 *       one lineage-grouped aggregation for the top-N heaviest archives.</li>
 *   <li>{@code POST /brain/{tenant}/cleanup/trash?projectId=X} — hard-
 *       delete every document under {@code _vance/trash/} in the scope.
 *       No body; the dialog confirms with the breakdown numbers it just
 *       loaded.</li>
 *   <li>{@code POST /brain/{tenant}/cleanup/archives?projectId=X} — keep
 *       the N newest archive entries per lineage, delete the rest. The
 *       dialog supplies {@code keepLatest} via the request body.</li>
 * </ul>
 *
 * <p><b>Auth.</b> Without {@code ?projectId=X} the caller must hold tenant
 * {@code ADMIN}. With it, project {@code ADMIN} is enough — a project
 * admin who empties their own project's trash should not need a tenant
 * admin in the loop. The breakdown endpoint follows the same rule.
 *
 * <p><b>Why the response carries quota numbers.</b> Each cleanup ends
 * with an explicit cache invalidation + re-aggregation (see
 * {@link StorageCleanupService}). The response bundles the result so the
 * UI renders the new meter without waiting for the {@code usageCache}
 * TTL. Cross-pod freshness is the existing 30 s backstop.
 */
@RestController
@RequestMapping("/brain/{tenant}")
@RequiredArgsConstructor
@Slf4j
public class StorageCleanupController {

    /** Optional REST header for the editor connection id — see DocumentController. */
    private static final String HEADER_EDITOR_ID = "X-Editor-Id";

    private final StorageCleanupService cleanupService;
    private final RequestAuthority authority;

    @GetMapping("/storage/breakdown")
    public StorageBreakdownDto breakdown(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest httpRequest) {
        enforceAdmin(httpRequest, tenant, projectId);
        return cleanupService.summarize(tenant, projectId);
    }

    @PostMapping("/cleanup/trash")
    public StorageCleanupResultDto emptyTrash(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestHeader(value = HEADER_EDITOR_ID, required = false) @Nullable String editorId,
            HttpServletRequest httpRequest) {
        enforceAdmin(httpRequest, tenant, projectId);
        return cleanupService.emptyTrash(tenant, projectId, writerIdentity(httpRequest, editorId), actor(httpRequest));
    }

    @PostMapping("/cleanup/archives")
    public StorageCleanupResultDto thinArchives(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestHeader(value = HEADER_EDITOR_ID, required = false) @Nullable String editorId,
            @RequestBody(required = false) @Nullable StorageCleanupRequest request,
            HttpServletRequest httpRequest) {
        enforceAdmin(httpRequest, tenant, projectId);
        return cleanupService.thinArchives(
                tenant,
                projectId,
                request == null ? null : request.getKeepLatest(),
                writerIdentity(httpRequest, editorId),
                actor(httpRequest));
    }

    // ──────────────────── auth helpers ────────────────────

    private void enforceAdmin(HttpServletRequest httpRequest, String tenant, @Nullable String projectId) {
        if (projectId == null || projectId.isBlank()) {
            authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        } else {
            authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.ADMIN);
        }
    }

    /** Mirrors DocumentController.writerIdentity — auth context carries the userId. */
    private DocumentService.WriterIdentity writerIdentity(HttpServletRequest request, @Nullable String editorId) {
        try {
            de.mhus.vance.shared.permission.SecurityContext ctx = authority.contextOf(request);
            String username = ctx.subjectId();
            return DocumentService.WriterIdentity.of(editorId, username, username);
        } catch (RuntimeException e) {
            return DocumentService.WriterIdentity.of(editorId, null, null);
        }
    }

    private WriteActor actor(HttpServletRequest request) {
        return WriteActor.user(authority.contextOf(request));
    }
}
