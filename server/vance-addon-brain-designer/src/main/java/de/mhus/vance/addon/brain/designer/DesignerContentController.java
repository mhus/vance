package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface of the designer app.
 *
 * <p>Three endpoints, two trust models:
 *
 * <ul>
 *   <li>{@code GET view} and {@code POST preview-session} are ordinary
 *       authenticated endpoints (cookie/bearer via the access filter,
 *       READ enforcement via {@link RequestAuthority}). They answer for
 *       the Web-UI, which holds a session.</li>
 *   <li>{@code GET content/…} is the sandboxed iframe's route. The iframe
 *       runs at an opaque origin ({@code sandbox="allow-scripts"}): no
 *       cookies, no headers, no ambient credentials at all. It
 *       authenticates with a short-lived {@code DESIGN_PREVIEW} JWT
 *       carried <em>as a path segment</em> — the position is the whole
 *       trick, because relative sub-resource URLs ({@code css/style.css})
 *       resolve inside the token prefix and keep carrying it. The
 *       {@code BrainAccessFilter} lets the path pass without a bearer;
 *       everything below re-validates the token here.</li>
 * </ul>
 *
 * <p><b>Why the per-request READ check on an already-scoped token.</b> The
 * token authenticates; it does not authorise. Each content fetch rebuilds a
 * {@link SecurityContext} from the token's claims (project-confined, see
 * {@link SecurityContextFactory#fromDesignPreviewClaims}) and enforces READ
 * on the addressed document — so a permission change takes effect on the
 * next fetch, not when the token expires, and a leaked token never grants
 * more than its minter had at fetch time.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class DesignerContentController {

    private static final String CONTENT_SEGMENT = "/addon/designer/content/";

    private final DesignerFolderReader folderReader;
    private final DesignerPreviewTokenService previewTokenService;
    private final DocumentService documentService;
    private final RequestAuthority authority;
    private final PermissionService permissionService;
    private final SecurityContextFactory securityContextFactory;
    private final DesignerApplication designerApplication;
    private final DesignerSkillService skillService;
    private final ThinkProcessService thinkProcessService;
    // ── Web-UI endpoints (session-authenticated) ───────────────────

    /**
     * Scans the app folder and returns the design catalogue plus the
     * manifest's title/description. Read-only, live — no registry
     * document in between.
     *
     * <p>Project READ is enforced here — the same visibility as the
     * design-skill listing: the catalogue names designs, titles and
     * file lists of the project folder, so it is project content,
     * not tenant-wide config.
     */
    @GetMapping("/brain/{tenant}/addon/designer/view")
    public DesignerView view(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        return loadView(tenant, projectId, folder);
    }

    /**
     * The catalogue, freshly scanned — the shared answer every endpoint
     * returns after a mutation, so the client replaces its state with one
     * shape.
     */
    private DesignerView loadView(String tenant, String projectId, String folder) {
        String normalised = DesignerPaths.normaliseFolder(folder);
        DesignerFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);

        String title = null;
        String description = null;
        Optional<DocumentDocument> manifest =
                documentService.findByPath(tenant, projectId, DesignerPaths.manifestPath(normalised));
        if (manifest.isPresent()) {
            try {
                var app = de.mhus.vance.shared.document.kind.ApplicationCodec.parse(
                        documentService.readContent(manifest.get()),
                        manifest.get().getMimeType());
                title = app.title();
                description = app.description();
            } catch (RuntimeException e) {
                // A manifest the codec cannot read still lists designs;
                // the app editor shows the raw file to whoever fixes it.
            }
        }

        List<DesignInfo> designs = scan.designs().stream()
                .map(d -> DesignInfo.builder()
                        .name(d.name())
                        .title(d.title())
                        .description(d.description())
                        .files(d.files())
                        .fileCount(d.fileCount())
                        .build())
                .toList();

        return DesignerView.builder()
                .folder(normalised)
                .manifestPath(DesignerPaths.manifestPath(normalised))
                .title(title)
                .description(description)
                .designs(designs)
                .build();
    }

    /**
     * Mints a preview session for the app folder — the credential the
     * sandboxed iframe embeds into its content URLs. READ on the app
     * manifest is enforced here; the content route re-checks READ on
     * every fetch.
     */
    @PostMapping("/brain/{tenant}/addon/designer/preview-session")
    public DesignerPreviewSession previewSession(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        String normalised = DesignerPaths.normaliseFolder(folder);
        authority.enforce(
                httpRequest,
                new Resource.Document(tenant, projectId, DesignerPaths.manifestPath(normalised)),
                Action.READ);
        String username = AccessFilterBase.usernameOrNull(httpRequest);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "preview session needs an authenticated user");
        }
        return previewTokenService.mint(tenant, projectId, normalised, username);
    }

    // ── Design mutations (session-authenticated) ─────────────────

    /**
     * Creates a design folder: entry file plus optional metadata.
     * Inbound adapter — validation and the writes live in the
     * application, these endpoints only enforce and answer.
     */
    @PostMapping("/brain/{tenant}/addon/designer/design")
    public DesignerView createDesign(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody DesignerDesignCreateRequest body,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        try {
            designerApplication.createDesign(
                    tenant,
                    projectId,
                    folder,
                    body.name(),
                    body.title(),
                    body.description(),
                    AccessFilterBase.usernameOrNull(httpRequest));
        } catch (ToolException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return loadView(tenant, projectId, folder);
    }

    /**
     * Moves every document of one design to the trash — the recoverable
     * delete, matching the document editor's delete.
     */
    @DeleteMapping("/brain/{tenant}/addon/designer/design")
    public DesignerView deleteDesign(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("name") String name,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        try {
            designerApplication.deleteDesign(
                    tenant, projectId, folder, name, AccessFilterBase.usernameOrNull(httpRequest));
        } catch (ToolException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
        return loadView(tenant, projectId, folder);
    }

    /** Persists the design order after a drag-and-drop in the catalogue. */
    @PostMapping("/brain/{tenant}/addon/designer/reorder")
    public DesignerView reorder(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody DesignerReorderRequest body,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        try {
            designerApplication.reorder(
                    tenant, projectId, folder, body.order(), AccessFilterBase.usernameOrNull(httpRequest));
        } catch (ToolException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
        return loadView(tenant, projectId, folder);
    }

    /**
     * Edits one design's {@code design.yaml} (title, description). Blank
     * values clear the key; foreign keys in the file survive.
     */
    @PostMapping("/brain/{tenant}/addon/designer/design-meta")
    public DesignerView updateDesignMeta(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody DesignerDesignMetaRequest body,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        try {
            designerApplication.updateDesignMeta(
                    tenant,
                    projectId,
                    folder,
                    body.name(),
                    body.title(),
                    body.description(),
                    AccessFilterBase.usernameOrNull(httpRequest));
        } catch (ToolException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
        return loadView(tenant, projectId, folder);
    }

    // ── Design skills (session-authenticated listing) ──────────────

    /**
     * Lists the design skills visible in the app's project scope: skills
     * tagged {@code design}, with the style.css convention reported per
     * skill. {@code sessionId}+{@code processName} are optional — when a
     * chat is open, the response joins in each skill's activation state
     * for that chat's think-process; without them the {@code active} flag
     * stays absent (there is no activation state without a process, and
     * "inactive" would be a claim the server cannot make).
     *
     * <p>READ on the project guards the listing; READ on the think-process
     * (the same resource the {@code process-skill} WS LIST enforces) guards
     * the active-state join. A stale process reference degrades to
     * "nothing active" instead of failing the whole catalogue.
     */
    @GetMapping("/brain/{tenant}/addon/designer/design-skills")
    public DesignerSkillList designSkills(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "sessionId", required = false) @Nullable String sessionId,
            @RequestParam(value = "processName", required = false) @Nullable String processName,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String username = AccessFilterBase.usernameOrNull(httpRequest);
        if (username == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "design-skill listing needs an authenticated user");
        }

        Map<String, Boolean> activeByRecipe = null;
        if (sessionId != null && !sessionId.isBlank() && processName != null && !processName.isBlank()) {
            Optional<ThinkProcessDocument> process = thinkProcessService.findByName(tenant, sessionId, processName);
            if (process.isPresent()) {
                ThinkProcessDocument found = process.get();
                authority.enforce(
                        httpRequest,
                        new Resource.ThinkProcess(
                                found.getTenantId(),
                                found.getProjectId(),
                                found.getSessionId(),
                                found.getId() == null ? "" : found.getId()),
                        Action.READ);
                activeByRecipe = new LinkedHashMap<>();
                for (ActiveSkillRefEmbedded ref : found.getActiveSkills()) {
                    // name → recipe-bound: the SkillPanel parity that lets
                    // the UI disable its clear button for recipe skills.
                    activeByRecipe.put(ref.getName(), ref.isFromRecipe());
                }
            } else {
                // The chat the client believes in has no process yet (or is
                // gone) — the truthful statement is "nothing is active",
                // not a failed catalogue.
                activeByRecipe = Map.of();
            }
        }

        return DesignerSkillList.builder()
                .skills(skillService.list(tenant, username, projectId, activeByRecipe))
                .build();
    }

    // ── Sandboxed content route (path-token authenticated) ─────────

    /**
     * Serves one file of one design for the sandboxed preview iframe:
     *
     * <pre>/brain/{tenant}/addon/designer/content/{appDocId}/{previewToken}/{design}/{inner…}</pre>
     *
     * <p>An empty (or trailing-slash) inner path maps to the design's
     * {@code index.html}, so the iframe can point at the design folder
     * and all relative references resolve naturally inside it.
     *
     * <p>Failures are deliberately coarse: a bad token, a foreign
     * document id, an unknown file and an escaped path all answer
     * {@code 404} — the route confirms nothing about what exists. A
     * valid token whose minter lost READ answers {@code 403} through the
     * permission service.
     */
    @GetMapping("/brain/{tenant}/addon/designer/content/{appDocId}/{token}/{design}/**")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable("tenant") String tenant,
            @PathVariable("appDocId") String appDocId,
            @PathVariable("token") String token,
            @PathVariable("design") String design,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatch,
            HttpServletRequest httpRequest) {

        VanceJwtClaims claims = previewTokenService
                .validate(token, tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // The URL's app document id pins the token's folder to a concrete
        // manifest — a token minted for one app cannot serve another, and
        // a wrong id says nothing about either.
        DocumentDocument appDoc =
                documentService.findById(appDocId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(appDoc.getTenantId())
                || !claims.projectId().equals(appDoc.getProjectId())
                || !appDoc.getPath().equals(DesignerPaths.manifestPath(claims.appFolder()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        String innerPath = innerPathAfter(httpRequest.getRequestURI());
        String fullPath = DesignerPaths.resolveFile(claims.appFolder(), design, innerPath)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        DocumentDocument doc = documentService
                .findByPath(tenant, claims.projectId(), fullPath)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        SecurityContext subject = securityContextFactory.fromDesignPreviewClaims(claims);
        permissionService.enforce(subject, new Resource.Document(tenant, claims.projectId(), fullPath), Action.READ);

        // Content version = storageId (fresh on every write); the same
        // rule as the document-content endpoint. no-cache = the browser
        // may keep the bytes but must revalidate — a design edit shows on
        // the next reload, an unchanged file is a cheap 304.
        String etag = doc.getStorageId() != null ? "\"" + doc.getStorageId() + "\"" : "\"" + doc.getId() + "\"";
        if (ifNoneMatch != null && etagsMatch(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.noCache())
                    .build();
        }

        MediaType contentType = parseMimeType(servedMime(fullPath, doc));
        long size = doc.getSize();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        // An opaque-origin iframe has no cookie jar, so private caching
        // directives are moot — but nosniff keeps a mislabelled file from
        // being re-interpreted as a content type the design did not declare.
        headers.set("X-Content-Type-Options", "nosniff");
        if (size > 0) {
            headers.setContentLength(size);
        }
        headers.setETag(etag);
        headers.setCacheControl("no-cache");
        // InputStreamResource, not the raw stream: Spring has no
        // HttpMessageConverter for a bare InputStream — the resource
        // form is what the document-content endpoint streams too.
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(documentService.loadContent(doc)));
    }

    // ── Skill style preview (path-token authenticated) ─────────────

    /**
     * Serves the style preview of one design skill for the catalogue's
     * small sandboxed boxes: the fixed demo body wrapped in the skill's
     * real {@code style.css}, inlined — one self-contained document, no
     * sub-resources, so the opaque-origin iframe needs no relative-URL
     * token carrying beyond this one request.
     *
     * <p>Same trust model as the content route: the {@code DESIGN_PREVIEW}
     * token authenticates (never as a bearer), the URL's app document id
     * pins it to one app manifest, and each fetch re-checks READ on that
     * manifest from the token claims. What the token extends to, beyond
     * the app folder files, is the design skills' stylesheets of the
     * token's project scope — the catalogue the same token's app already
     * shows. Failures stay coarse: bad token, wrong pin, unknown or
     * style-less skill all answer {@code 404}.
     */
    @GetMapping("/brain/{tenant}/addon/designer/skill-preview/{appDocId}/{token}")
    public ResponseEntity<String> skillPreview(
            @PathVariable("tenant") String tenant,
            @PathVariable("appDocId") String appDocId,
            @PathVariable("token") String token,
            @RequestParam("skill") String skillName) {

        VanceJwtClaims claims = previewTokenService
                .validate(token, tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        DocumentDocument appDoc =
                documentService.findById(appDocId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(appDoc.getTenantId())
                || !claims.projectId().equals(appDoc.getProjectId())
                || !appDoc.getPath().equals(DesignerPaths.manifestPath(claims.appFolder()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // The token authenticates; READ re-checked per fetch authorises —
        // the same rule every content fetch follows.
        SecurityContext subject = securityContextFactory.fromDesignPreviewClaims(claims);
        permissionService.enforce(
                subject, new Resource.Document(tenant, claims.projectId(), appDoc.getPath()), Action.READ);

        String css = skillService
                .readStyle(tenant, claims.username(), claims.projectId(), skillName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl("no-cache");
        return ResponseEntity.ok().headers(headers).body(DesignerSkillService.renderPreviewHtml(css));
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Extracts the inner path (everything after the design segment) from
     * the request URI, percent-decoding each segment. Decoding segment
     * by segment — not the whole tail — keeps an encoded slash ({@code
     * %2F}) from smuggling a path separator past the validation that
     * follows.
     */
    static String innerPathAfter(String requestUri) {
        int contentAt = requestUri.indexOf(CONTENT_SEGMENT);
        if (contentAt < 0) {
            return "";
        }
        String[] segments =
                requestUri.substring(contentAt + CONTENT_SEGMENT.length()).split("/");
        // content/{appDocId}/{token}/{design}/{inner…}
        StringBuilder inner = new StringBuilder();
        for (int i = 3; i < segments.length; i++) {
            if (i > 3) {
                inner.append('/');
            }
            inner.append(percentDecode(segments[i]));
        }
        return inner.toString();
    }

    /** Percent-decodes one path segment; {@code +} stays a literal plus. */
    private static String percentDecode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%' && i + 2 < segment.length()) {
                int hi = Character.digit(segment.charAt(i + 1), 16);
                int lo = Character.digit(segment.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(c);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * The mime the preview actually serves, with a serving-time correction
     * for the two file types a wrong stored mime visibly breaks: an
     * {@code index.html} stored as {@code text/markdown} renders as raw
     * source (nosniff) instead of the design — the historical
     * doc_write-kind-default bug. The extension is the design's own
     * declaration; deliberately only {@code .html} and {@code .css} —
     * every other file keeps its stored mime untouched.
     *
     * <p>This is the safety net for documents that already carry the wrong
     * mime, not a licence to store one: {@code designer_validate} still
     * reports the stored mismatch so the data gets fixed at the source.
     */
    static String servedMime(String fullPath, DocumentDocument doc) {
        String lower = fullPath.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        return doc.getMimeType();
    }

    /** Lenient {@code If-None-Match} match — wildcard, list form, quoting. */
    private static boolean etagsMatch(String ifNoneMatch, String etag) {
        String header = ifNoneMatch.trim();
        if ("*".equals(header)) return true;
        for (String raw : header.split(",")) {
            if (raw.trim().equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static MediaType parseMimeType(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(raw);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
