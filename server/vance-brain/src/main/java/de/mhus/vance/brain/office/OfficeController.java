package de.mhus.vance.brain.office;

import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.net.SsrfGuard;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the ONLYOFFICE / Collabora integration. Three
 * endpoints, two of them called by the *document server* on behalf
 * of an editing user:
 *
 * <ul>
 *   <li>{@code GET /brain/{tenant}/office/session/{docId}} — called
 *       by the Vance Web-UI to build the editor config (JS-SDK URL,
 *       document key, JWT for the doc-server, callback URLs).
 *       Behind the regular Vance access filter (user JWT).</li>
 *   <li>{@code GET /brain/{tenant}/office/download/{docId}?token=…}
 *       — called by the document server to fetch the bytes of the
 *       document being edited. Token-authenticated; outside the
 *       Vance access filter (see {@code BrainAccessFilter}).</li>
 *   <li>{@code POST /brain/{tenant}/office/callback/{docId}?token=…}
 *       — called by the document server when the user saves or
 *       closes the editor. Carries the new bytes (we download them
 *       via the URL the doc-server hands us). Token-authenticated.</li>
 * </ul>
 *
 * <p>JWT keys are per-tenant (see {@link OfficeSettings}).
 */
@RestController
@RequestMapping("/brain/{tenant}/office")
@RequiredArgsConstructor
@Slf4j
public class OfficeController {

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(60);

    private final OfficeSettings officeSettings;
    private final OfficeJwtService jwtService;
    private final DocumentService documentService;
    private final de.mhus.vance.brain.permission.RequestAuthority authority;

    @Value("${vance.web.publicBaseUrl}")
    private String publicBaseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .build();

    // ─── Session endpoint (called from Vance Web-UI) ─────────────────

    /**
     * Build the editor-config envelope the Vance Web-UI hands to the
     * ONLYOFFICE JS-SDK. Resolves the office settings for this
     * document's project, generates a fresh document-key and
     * download/callback URLs, and signs the matching JWT.
     */
    @GetMapping("/session/{docId}")
    public ResponseEntity<Map<String, Object>> session(
            @PathVariable("tenant") String tenant,
            @PathVariable("docId") String docId,
            @RequestParam(name = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {

        DocumentDocument doc = loadDocument(tenant, docId);
        // The session hands the browser an edit-enabled ONLYOFFICE config,
        // so gate it on WRITE for this document before issuing any token.
        // (download/callback are machine-to-machine and JWT-scoped by this
        // grant.)
        authority.enforce(request,
                new de.mhus.vance.shared.permission.Resource.Document(
                        tenant, projectId != null ? projectId : doc.getProjectId(), doc.getPath()),
                de.mhus.vance.shared.permission.Action.WRITE);
        OfficeSettings.Snapshot office = officeSettings.resolve(
                tenant, projectId != null ? projectId : doc.getProjectId());

        if (!office.isEnabled()) {
            return ResponseEntity
                    .status(503)
                    .body(Map.of(
                            "error", "office-not-configured",
                            "message", "Office editor is not configured "
                                    + "for this tenant/project"));
        }

        String documentKey = OfficeDocumentKey.of(doc);
        String docExtension = OfficeFileType.extension(doc.getMimeType());
        String docType = OfficeFileType.docType(docExtension);
        String callbackBase = effectiveCallbackBaseUrl(office);
        String downloadUrl = callbackBase + "/brain/" + tenant
                + "/office/download/" + urlEncode(docId)
                + "?token=" + jwtService.sign(office, "download",
                        Map.of("docId", docId, "tenant", tenant));
        String callbackUrl = callbackBase + "/brain/" + tenant
                + "/office/callback/" + urlEncode(docId)
                + "?token=" + jwtService.sign(office, "callback",
                        Map.of("docId", docId, "tenant", tenant));

        // ONLYOFFICE expects the editor-config to be JWT-signed too
        // (when the document server runs with JWT enabled). The token
        // wraps the full payload — the SDK on the browser side picks
        // it up. We sign with the same secret as the callback path.
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", docExtension);
        document.put("key", documentKey);
        document.put("title", doc.getTitle() != null
                ? doc.getTitle() : doc.getPath());
        document.put("url", downloadUrl);
        // Without explicit permissions ONLYOFFICE falls into a
        // read-only mode where the Save button stays disabled even
        // though `mode=edit` is set. Enable the standard edit
        // surface; the brain still does the final ACL check on
        // every callback via the JWT-token contents.
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("edit", true);
        permissions.put("download", true);
        permissions.put("print", true);
        permissions.put("review", true);
        permissions.put("comment", true);
        document.put("permissions", permissions);

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("callbackUrl", callbackUrl);
        editorConfig.put("mode", "edit");
        // ONLYOFFICE refuses to enable the Save button without a
        // {id, name} user — even when mode=edit and permissions.edit
        // are set. We pull the authenticated user out of the request
        // attributes the BrainAccessFilter populated; fall back to a
        // tenant-scoped anonymous identity so unauthenticated test
        // calls still get something deterministic.
        String username = userFromRequest(request);
        Map<String, Object> userBlock = new LinkedHashMap<>();
        userBlock.put("id", username != null ? username : "anonymous@" + tenant);
        userBlock.put("name", username != null ? username : "User");
        editorConfig.put("user", userBlock);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document", document);
        payload.put("documentType", docType);
        payload.put("editorConfig", editorConfig);
        String configToken = jwtService.sign(office, "config", payload);

        Map<String, Object> response = new LinkedHashMap<>(payload);
        response.put("token", configToken);
        response.put("officeUrl", office.url());
        response.put("provider", office.provider());
        log.info("OfficeController.session tenant='{}' docId='{}' "
                        + "version={} key='{}' user='{}'",
                tenant, docId, doc.getVersion(), documentKey,
                userBlock.get("id"));
        return ResponseEntity.ok(response);
    }

    // ─── Download endpoint (called by ONLYOFFICE) ────────────────────

    /**
     * Document server fetches the bytes via this URL. We verify
     * the JWT, load the document content, stream it out.
     */
    @GetMapping("/download/{docId}")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable("tenant") String tenant,
            @PathVariable("docId") String docId,
            @RequestParam("token") String token) {

        DocumentDocument doc = loadDocument(tenant, docId);
        OfficeSettings.Snapshot office = officeSettings.resolve(
                tenant, doc.getProjectId());
        if (!office.isEnabled()) {
            return ResponseEntity.status(503).build();
        }

        Claims claims = jwtService.verify(office, token);
        if (claims == null
                || !docId.equals(claims.get("docId", String.class))
                || !"download".equals(claims.get("typ", String.class))) {
            return ResponseEntity.status(401).build();
        }

        InputStream content = documentService.loadContent(doc);
        InputStreamResource body = new InputStreamResource(content);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                org.springframework.http.ContentDisposition
                        .builder("inline")
                        .filename(filenameFor(doc))
                        .build());
        String mime = doc.getMimeType() != null
                ? doc.getMimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .headers(headers)
                .body(body);
    }

    // ─── Callback endpoint (called by ONLYOFFICE) ────────────────────

    /**
     * Document server posts here when the user saves or closes the
     * editor. Payload shape: {@code { status: int, url?: string,
     * key: string, ... }}. We act only on save-events (status 2 or
     * 6 — see ONLYOFFICE docs). For those we download the new bytes
     * from {@code url} and update the Vance Document.
     */
    @PostMapping("/callback/{docId}")
    public ResponseEntity<Map<String, Object>> callback(
            @PathVariable("tenant") String tenant,
            @PathVariable("docId") String docId,
            @RequestParam("token") String token,
            @RequestBody Map<String, Object> payload) {

        DocumentDocument doc = loadDocument(tenant, docId);
        OfficeSettings.Snapshot office = officeSettings.resolve(
                tenant, doc.getProjectId());
        if (!office.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", 1, "message", "office disabled"));
        }

        Claims claims = jwtService.verify(office, token);
        if (claims == null
                || !docId.equals(claims.get("docId", String.class))
                || !"callback".equals(claims.get("typ", String.class))) {
            log.warn("OfficeController: callback token rejected for docId={}", docId);
            return ResponseEntity.status(401)
                    .body(Map.of("error", 1, "message", "invalid token"));
        }

        int status = numericStatus(payload.get("status"));
        // Status 2 = MustSave (last user closed), 6 = ForceSave
        // (explicit save request). Others (1/3/4/7) are
        // informational and need no body update — but we still ACK
        // with {error:0} so the document server stops retrying.
        if (status == 2 || status == 6) {
            String url = asString(payload.get("url"));
            if (url == null || url.isBlank()) {
                log.warn("OfficeController: save callback for docId={} "
                        + "had no url field", docId);
                return ResponseEntity.ok(Map.of("error", 1,
                        "message", "missing url"));
            }
            try {
                byte[] bytes = fetchBytes(url);
                String mime = doc.getMimeType() != null
                        ? doc.getMimeType()
                        : MediaType.APPLICATION_OCTET_STREAM_VALUE;
                // OnlyOffice save = a user edit → gate the soft lock as USER
                // (code-review F6). The editing user's subject is a real,
                // non-sentinel editorId, so writerRoleOf maps it to USER.
                String editorSub = asString(claims.get("sub"));
                documentService.replaceBinaryContent(doc.getId(), mime, bytes,
                        editorSub,
                        DocumentService.WriterIdentity.of(editorSub, editorSub, null),
                        de.mhus.vance.shared.permission.WriteActor.SYSTEM);
                log.info("OfficeController: saved docId={} bytes={} status={}",
                        docId, bytes.length, status);
            } catch (Exception e) {
                log.warn("OfficeController: download from doc-server failed "
                        + "docId={} url={} — {}", docId, url, e.getMessage());
                return ResponseEntity.ok(Map.of("error", 1,
                        "message", "download failed: " + e.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of("error", 0));
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /** Extract the authenticated username from the request
     *  attributes the {@code BrainAccessFilter} set after JWT
     *  validation. Returns {@code null} when no user is bound
     *  (admin / unauthenticated paths). */
    static @Nullable String userFromRequest(@Nullable HttpServletRequest request) {
        if (request == null) return null;
        Object raw = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        return (raw instanceof String s && !s.isBlank()) ? s : null;
    }

    /**
     * Pick the base URL the document server uses to reach the
     * Vance brain. Per-tenant {@code office.callbackBaseUrl}
     * override wins when set, otherwise the global
     * {@code vance.web.publicBaseUrl}. Trailing slash stripped so
     * URL-joins don't double-slash.
     */
    String effectiveCallbackBaseUrl(OfficeSettings.Snapshot office) {
        String override = office.callbackBaseUrl();
        String chosen = (override != null && !override.isBlank())
                ? override : publicBaseUrl;
        return stripTrailingSlash(chosen);
    }

    static String stripTrailingSlash(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    private DocumentDocument loadDocument(String tenant, String docId) {
        DocumentDocument doc = documentService.findById(docId)
                .orElseThrow(() -> new IllegalStateException(
                        "Document not found: " + docId));
        if (!tenant.equals(doc.getTenantId())) {
            throw new IllegalStateException(
                    "Document " + docId + " is not in tenant " + tenant);
        }
        return doc;
    }

    private byte[] fetchBytes(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> res;
        try {
            // SsrfGuard: the callback url is client-supplied — block fetches
            // aimed at internal/metadata addresses (F2). Client already
            // defaults to Redirect.NEVER, so sendGuarded owns redirects.
            res = SsrfGuard.sendGuarded(httpClient, req,
                    SsrfGuard.capped(HttpResponse.BodyHandlers.ofByteArray()));
        } catch (SsrfGuard.SsrfException e) {
            throw new IOException("doc-server url rejected: " + e.getMessage(), e);
        }
        if (res.statusCode() / 100 != 2) {
            throw new IOException("doc-server returned HTTP " + res.statusCode());
        }
        return res.body();
    }

    static int numericStatus(@Nullable Object raw) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    static @Nullable String asString(@Nullable Object v) {
        return v == null ? null : v.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String filenameFor(DocumentDocument doc) {
        String path = doc.getPath();
        if (path == null) return doc.getId();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
