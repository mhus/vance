package de.mhus.vance.brain.backup;

import de.mhus.vance.api.backup.BackupEntriesRequest;
import de.mhus.vance.api.backup.BackupEntriesResponse;
import de.mhus.vance.api.backup.BackupEntryDto;
import de.mhus.vance.api.backup.BackupInfoResponse;
import de.mhus.vance.api.backup.BackupProjectDto;
import de.mhus.vance.api.backup.BackupProjectsRequest;
import de.mhus.vance.api.backup.BackupProjectsResponse;
import de.mhus.vance.api.backup.BackupRunsRequest;
import de.mhus.vance.api.backup.BackupRunsResponse;
import de.mhus.vance.api.documents.AgeDocumentKind;
import de.mhus.vance.api.util.PathGlob;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.integration.IntegrationTokenService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The five REST surfaces of the {@code vance-liver} backup tool —
 * {@code planning/user-backup.md} §6.1.
 *
 * <p>Auth is handled by {@link IntegrationTokenAuthService} +
 * {@code NarrowedCredentialCheck} on the request's {@code scp} claim;
 * the {@code backup} profile declares exactly these five routes plus
 * the documented self-revoke carve-out for {@code /current}. The
 * controller therefore does not re-check the scope — that has already
 * happened by the time a request reaches any method here. What it does
 * check, per call, is the per-tenant policy
 * ({@link BackupAccessService}) and the project pin.
 *
 * <p>Two distinct request kinds sit next to each other:
 *
 * <ul>
 *   <li><b>POST</b> for the list calls ({@code /projects}, {@code /entries})
 *       because the filter lists travel in the body. Query strings would
 *       put deny-globs into access logs — exactly the secrets the user
 *       is trying to keep local.</li>
 *   <li><b>GET</b> for the content fetch — cache-friendly, supports the
 *       ETag = contentVersion flow that lets the tool skip unchanged
 *       bytes on a re-run.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class BackupController {

    /** Manifest format version this server emits. Bumped on a breaking change to {@code entries.jsonl}. */
    static final int MANIFEST_FORMAT = 1;

    private final DocumentService documentService;
    private final ProjectService projectService;
    private final IntegrationTokenService integrationTokenService;
    private final BackupAccessService backupAccessService;
    private final RequestAuthority authority;
    private final SecurityContextFactory securityContextFactory;

    // ─── GET /backup/info ─────────────────────────────────────────────

    @GetMapping("/brain/{tenant}/backup/info")
    public BackupInfoResponse info(@PathVariable String tenant, HttpServletRequest request) {
        String username = requireUser(request);
        // Policy gate first: when the answer is "no", the tool gets the
        // explanation here rather than a mysterious 403 on the next call.
        BackupAccessConfig policy = backupAccessService.load(tenant);
        if (!policy.admits(username)) {
            return BackupInfoResponse.builder()
                    .allowed(false)
                    .denialReason(
                            "backup is disabled for tenant '" + tenant + "', or your account is not on the allow-list")
                    .serverVersion(serverVersion())
                    .manifestFormat(MANIFEST_FORMAT)
                    .idleDaysCeiling(policy.effectiveIdleDaysCeiling())
                    .build();
        }

        // Token-derived metadata: the same jti this request authenticated
        // with, looked up to learn the current expiresAt. A backup tool
        // that just extended its token sees the new expiry echoed here.
        IntegrationTokenInfo tokenInfo = currentTokenInfo(tenant, request);
        return BackupInfoResponse.builder()
                .allowed(true)
                .serverVersion(serverVersion())
                .manifestFormat(MANIFEST_FORMAT)
                .tokenExpiresAtMs(toMillis(tokenInfo.expiresAt()))
                .idleDaysCeiling(policy.effectiveIdleDaysCeiling())
                .allowedSurfaces(List.of(
                        "GET /backup/info",
                        "POST /backup/projects",
                        "POST /backup/projects/*/entries",
                        "GET /backup/documents/*/content",
                        "POST /backup/runs"))
                .build();
    }

    // ─── POST /backup/projects ────────────────────────────────────────

    @PostMapping("/brain/{tenant}/backup/projects")
    public BackupProjectsResponse listProjects(
            @PathVariable String tenant,
            @Valid @RequestBody(required = false) @Nullable BackupProjectsRequest req,
            HttpServletRequest request) {
        String username = requireUser(request);
        BackupAccessConfig policy = backupAccessService.load(tenant);
        if (!policy.admits(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, policyDenial(tenant));
        }

        List<String> include = req == null ? List.of() : nonNull(req.getInclude());
        List<String> exclude = req == null ? List.of() : nonNull(req.getExclude());
        boolean includeDisabled = req != null && req.isIncludeDisabled();

        // Disabled projects are only backed up on request (planning §5):
        // drop them from the candidate list unless includeDisabled is set,
        // then run the same READ filter listReadableBy uses.
        List<ProjectDocument> candidates = new ArrayList<>(projectService.all(tenant));
        if (!includeDisabled) {
            candidates.removeIf(p -> !p.isEnabled());
        }
        List<ProjectDocument> readable = new ArrayList<>(
                projectService.filterReadable(tenant, securityContextFactory.fromRequest(request), candidates));

        // Apply include / exclude globs on top. The client sends the same
        // PathGlob-compiled lists itself; rejecting a pattern here surfaces
        // a typo at the first call rather than at the manifest write.
        List<PathGlob> includeGlobs = compileGlobs(include);
        List<PathGlob> excludeGlobs = compileGlobs(exclude);
        List<BackupProjectDto> out = new ArrayList<>(readable.size());
        for (ProjectDocument p : readable) {
            String name = p.getName();
            if (!include.isEmpty() && !PathGlob.anyMatch(includeGlobs, name)) continue;
            if (!exclude.isEmpty() && PathGlob.anyMatch(excludeGlobs, name)) continue;
            // 'enabled' on the response reflects the project as it stands
            // now — the manifest records it so a re-scan knows what was
            // there at snapshot time.
            out.add(BackupProjectDto.builder()
                    .name(name)
                    .title(p.getTitle())
                    .enabled(p.isEnabled())
                    .build());
        }
        return BackupProjectsResponse.builder().projects(out).build();
    }

    // ─── POST /backup/projects/{project}/entries ──────────────────────

    @PostMapping("/brain/{tenant}/backup/projects/{project}/entries")
    public BackupEntriesResponse listEntries(
            @PathVariable String tenant,
            @PathVariable String project,
            @Valid @RequestBody(required = false) @Nullable BackupEntriesRequest req,
            HttpServletRequest request) {
        String username = requireUser(request);
        BackupAccessConfig policy = backupAccessService.load(tenant);
        if (!policy.admits(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, policyDenial(tenant));
        }
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        // Per-call deny list = the client's global deny + its deny for this
        // project; a path matching either is left out. The default entry
        // "_vance/**" lives in the client config, where the user can remove
        // it (planning §5) — adding it here would make it unremovable.
        List<String> deny = new ArrayList<>();
        if (req != null) deny.addAll(nonNull(req.getDeny()));
        if (req != null) deny.addAll(nonNull(req.getProjectDeny()));
        int size = clampSize(req == null ? 0 : req.getSize());
        @Nullable String cursor = req == null ? null : req.getCursor();

        DocumentService.BackupPage page = documentService.listBackupPage(tenant, project, deny, cursor, size);
        List<BackupEntryDto> entries = new ArrayList<>(page.rows().size());
        long bytesTotal = 0L;
        for (DocumentDocument row : page.rows()) {
            entries.add(toEntry(row));
            bytesTotal += row.getSize();
        }
        // The cursor follows the rows the service scanned, not the ones that
        // survived the deny filter — a filtered-short page is not the last.
        return BackupEntriesResponse.builder()
                .entries(entries)
                .nextCursor(page.nextAfterId())
                .bytesTotal(bytesTotal)
                .build();
    }

    // ─── GET /backup/documents/{id}/content ───────────────────────────

    @GetMapping("/brain/{tenant}/backup/documents/{id}/content")
    public ResponseEntity<InputStreamResource> fetchContent(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatch,
            HttpServletRequest request) {
        String username = requireUser(request);
        BackupAccessConfig policy = backupAccessService.load(tenant);
        if (!policy.admits(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, policyDenial(tenant));
        }
        DocumentDocument doc = documentService.findById(id).orElse(null);
        if (doc == null || !tenant.equals(doc.getTenantId())) {
            // Tenant path is in the URL but the doc's tenant is what
            // matters; a 404 here also covers "doc is in another tenant"
            // without leaking that fact to a caller who guessed an id.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "document '" + id + "' not found");
        }
        authority.enforce(request, new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.READ);

        // InputStreamResource (rather than raw InputStream) — Spring's
        // generic InputStream converter would 500 on a streaming source
        // because there is no registered MessageConverter for it; the
        // Resource path has StreamingResponseBody + ByteRange support.
        String etag = "\"" + contentVersion(doc) + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        if (etag.equals(ifNoneMatch)) {
            // The client already holds these bytes — storageId changes on
            // every content write, so an equal tag means identical content.
            return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
        }
        InputStream stream = documentService.loadContent(doc);
        headers.setContentType(mediaTypeOf(doc));
        return new ResponseEntity<>(new InputStreamResource(stream), headers, HttpStatus.OK);
    }

    // ─── POST /backup/runs ────────────────────────────────────────────

    @PostMapping("/brain/{tenant}/backup/runs")
    public BackupRunsResponse reportRun(
            @PathVariable String tenant, @Valid @RequestBody BackupRunsRequest req, HttpServletRequest request) {
        String username = requireUser(request);
        BackupAccessConfig policy = backupAccessService.load(tenant);
        if (!policy.admits(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, policyDenial(tenant));
        }
        // Token identity comes from the verified claims, not the body —
        // a body that says "user=root" gets ignored. Minted for themselves
        // is a structural invariant; the same report would be useless if
        // a thief could attribute it to anyone else.
        VanceJwtClaims claims = requireIntegrationToken(request);

        // Per planning §7.6, only a successful run extends the token.
        // An aborted run records the attempt (under "Apps mit Zugriff")
        // but does not push the expiry forward — a tool that hangs
        // should not gain a longer lease for not finishing.
        if (!req.isSucceeded()) {
            log.info(
                    "Backup run reported (failed) tenant='{}' user='{}' tool='{}' docs={} bytes={}",
                    tenant,
                    username,
                    req.getToolVersion(),
                    req.getDocumentsCount(),
                    req.getBytesDownloaded());
            return new BackupRunsResponse(false, null, policy.effectiveIdleDaysCeiling());
        }

        // Attempt extend first; log if the policy refused or the token is
        // no longer sliding-lifetime (a downgrade that the controller can
        // survive without losing the run record).
        boolean extended = integrationTokenService.extend(claims.tokenId());
        Long newExpiry = extended ? readNewExpiry(tenant, claims.tokenId()) : null;
        log.info(
                "Backup run reported tenant='{}' user='{}' tool='{}' docs={} bytes={} extended={}",
                tenant,
                username,
                req.getToolVersion(),
                req.getDocumentsCount(),
                req.getBytesDownloaded(),
                extended);
        return new BackupRunsResponse(extended, newExpiry, policy.effectiveIdleDaysCeiling());
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static BackupEntryDto toEntry(DocumentDocument doc) {
        return BackupEntryDto.builder()
                .id(doc.getId())
                .projectId(doc.getProjectId())
                .path(doc.getPath())
                .lineageId(doc.getLineageId())
                .kind(doc.getKind())
                .title(doc.getTitle())
                .mimeType(doc.getMimeType())
                .size(doc.getSize())
                .createdAtMs(toMillis(doc.getCreatedAt()))
                .updatedAtMs(toMillis(doc.getUpdatedAt()))
                .contentVersion(contentVersion(doc))
                .encrypted(doc.getMimeType() != null && AgeDocumentKind.isAgeEncrypted(null, doc.getMimeType()))
                .build();
    }

    private static String contentVersion(DocumentDocument doc) {
        // Same rule as DocumentController.contentEtag: storageId is the
        // byte-identity of the body; id is the fallback for inline/empty
        // documents whose bytes live on the row.
        return doc.getStorageId() != null ? doc.getStorageId() : doc.getId();
    }

    private static MediaType mediaTypeOf(DocumentDocument doc) {
        String mime = doc.getMimeType();
        if (mime == null || mime.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(mime);
        } catch (org.springframework.util.InvalidMimeTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static Long toMillis(java.time.Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static int clampSize(int requested) {
        // 1..1000 keeps a single page from streaming the world and keeps
        // the cursor useful — a tool that pages by 1000 already gets the
        // whole tenant in a handful of calls.
        if (requested <= 0) return 500;
        return Math.min(requested, 1000);
    }

    private static List<String> nonNull(@Nullable List<String> list) {
        return list == null ? List.of() : list;
    }

    private static List<PathGlob> compileGlobs(List<String> patterns) {
        if (patterns.isEmpty()) return List.of();
        // compileAll rejects on the first typo — that is the point. The
        // client gets a clear 400 rather than a manifest full of silent
        // misses on the next run.
        return PathGlob.compileAll(patterns);
    }

    private static String requireUser(HttpServletRequest request) {
        String username = AccessFilterBase.usernameOrNull(request);
        if (username == null) {
            // Defensive — the filter would have rejected this already.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return username;
    }

    private static VanceJwtClaims requireIntegrationToken(HttpServletRequest request) {
        Object claims = request.getAttribute(AccessFilterBase.ATTR_CLAIMS);
        if (!(claims instanceof VanceJwtClaims c) || c.tokenType() != TokenType.INTEGRATION) {
            // /backup/runs is the sliding-lifetime liveness signal; only an
            // INTEGRATION token can drive it. A non-INTEGRATION credential
            // reaching this endpoint is a caller bug.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Only integration tokens can report a backup run");
        }
        return c;
    }

    private static String policyDenial(String tenant) {
        return "backup is disabled for tenant '" + tenant
                + "', or your account is not on the allow-list (see _vance/config/backup-access.yaml)";
    }

    private static String serverVersion() {
        // Cheap and stable; the only point is to tell the user "you are
        // running against server X.Y.Z" so a mismatch on either side
        // surfaces in their logs, not as silent bugs.
        return java.util.Optional.ofNullable(BackupController.class.getPackage().getImplementationVersion())
                .orElse("dev");
    }

    /**
     * Read the current expiry after {@link IntegrationTokenService#extend}
     * ran. Goes through the same lookup {@code extend} did; if the row
     * moved (concurrent revoke) the result is null and the client treats
     * it as "your token is gone" rather than a confusing stale value.
     */
    private @Nullable Long readNewExpiry(String tenant, @Nullable String tokenId) {
        if (tokenId == null) return null;
        Optional<de.mhus.vance.shared.integration.IntegrationTokenDocument> row =
                integrationTokenService.find(tenant, tokenId);
        return row.map(r -> toMillis(r.getExpiresAt())).orElse(null);
    }

    /**
     * The token identity (jti) and the integration token's current expiry.
     * Used by {@link #info} so the tool gets the post-extend expiry
     * without making a separate call.
     */
    private record IntegrationTokenInfo(String tokenId, java.time.Instant expiresAt) {
        // Both fields are intrinsically optional; the helper that builds
        // this record passes null when the token metadata is unavailable.
        // Kept as plain reference types here (no @Nullable) because record
        // components plus @NullMarked package-info annotate the accessors
        // implicitly — explicit annotations fight Lombok at this level.
    }

    private IntegrationTokenInfo currentTokenInfo(String tenant, HttpServletRequest request) {
        Object claims = request.getAttribute(AccessFilterBase.ATTR_CLAIMS);
        if (!(claims instanceof VanceJwtClaims c) || c.tokenType() != TokenType.INTEGRATION) {
            // Should not happen — the filter has already authenticated an
            // INTEGRATION token by the time we reach the controller — but
            // the type is erased at this boundary, so be defensive.
            return new IntegrationTokenInfo(null, null);
        }
        Optional<de.mhus.vance.shared.integration.IntegrationTokenDocument> row =
                integrationTokenService.find(tenant, c.tokenId());
        return new IntegrationTokenInfo(
                c.tokenId(), row.map(r -> r.getExpiresAt()).orElse(null));
    }
}
