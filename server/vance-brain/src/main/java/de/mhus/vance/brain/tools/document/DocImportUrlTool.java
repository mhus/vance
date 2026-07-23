package de.mhus.vance.brain.tools.document;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.brain.tools.web.InsecureHttpClientFactory;
import de.mhus.vance.shared.net.SsrfGuard;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Imports a URL as a document in the active project. Fetches the
 * URL, then persists the response body via
 * {@link DocumentService#create}.
 *
 * <p>Distinct from {@code web_fetch} (which truncates and returns the
 * body inline for one-shot consumption): {@code doc_import_url}
 * stores the full body persistently and indexes it as a regular
 * project document. Use it when the user wants to refer back to the
 * page later or have it available for RAG.
 */
@Component
@Slf4j
public class DocImportUrlTool implements Tool {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Maximum import size — generous because the body lives in
     * Mongo / storage afterwards, not in an LLM call. 10 MB covers
     * modern 4K JPEGs, mid-size PDFs, and most full-page article
     * snapshots; bigger imports need a deliberate scripted ingest
     * rather than an LLM-triggered call.
     */
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    private static final String USER_AGENT = "Vance-Brain/0.1 (+https://github.com/mhus/vance)";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name. Defaults "
                                    + "to the active project."),
                    "url", Map.of(
                            "type", "string",
                            "description", "Absolute http:// or https:// URL "
                                    + "to fetch and import."),
                    "path", Map.of(
                            "type", "string",
                            "description", "Optional document path inside the project, "
                                    + "e.g. 'documents/imports/apollo-13.html'. "
                                    + "Must be unique per project. Omitted → auto-"
                                    + "generated under 'documents/' from the URL's "
                                    + "last path segment (or title slug)."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional human title (defaults to the URL)."),
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Optional tags. 'imported' is "
                                    + "added automatically."),
                    "summary", Map.of(
                            "type", "string",
                            "description", "Optional summary / caption to "
                                    + "store on the document. Especially "
                                    + "useful for binary content (images, "
                                    + "PDFs) where the auto-summary "
                                    + "scheduler doesn't run. Slideshows "
                                    + "fall back to this when no caption "
                                    + "is set in the manifest."),
                    "insecure", Map.of(
                            "type", "boolean",
                            "description", "Skip TLS certificate "
                                    + "verification for this single fetch. "
                                    + "Opt-in escape hatch for sites with "
                                    + "broken cert chains — e.g. a leaf-"
                                    + "only certificate without the "
                                    + "intermediate, where AIA chasing "
                                    + "also fails. Only set when the user "
                                    + "explicitly asks; the call is logged "
                                    + "as a warning."),
                    "ifExists", Map.of(
                            "type", "string",
                            "enum", List.of("reuse", "update", "error"),
                            "description", "What to do when a document "
                                    + "already exists at the target path. "
                                    + "'reuse' (default): return the existing "
                                    + "doc without re-fetching — idempotent, "
                                    + "use this when the URL may have been "
                                    + "imported before. 'update': fetch the "
                                    + "URL and replace the existing body and "
                                    + "mime-type (title and tags are kept). "
                                    + "'error': fail with an error.")),
            "required", List.of("url"));

    private static final Set<String> VALID_IF_EXISTS = Set.of("reuse", "update", "error");

    // Redirect.NEVER so SsrfGuard.sendGuarded re-checks every hop (F2).
    private final HttpClient http = SsrfGuard.guardedClientBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public DocImportUrlTool(EddieContext eddieContext, DocumentService documentService,
            de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    @Override
    public String name() {
        return "doc_import_url";
    }

    @Override
    public String description() {
        return "Fetch a URL and persist the body as a project "
                + "document. Use when the user wants to keep / refer "
                + "to the page (article, doc, snippet). For one-shot "
                + "reads use web_fetch instead. Idempotent by default: "
                + "if the target path already exists the existing doc "
                + "is returned (set ifExists='update' to refresh).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "document");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String rawUrl = paramString(params, "url");
        String path = paramString(params, "path");
        if (rawUrl == null) throw new ToolException("'url' is required");

        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new ToolException("Invalid URL: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http")
                        && !scheme.equalsIgnoreCase("https"))) {
            throw new ToolException(
                    "Only http:// and https:// URLs are supported");
        }

        String title = paramString(params, "title");
        if (title == null) {
            title = rawUrl;
        }
        if (path == null) {
            path = autoPath(uri, title);
        }
        List<String> tags = paramStringList(params, "tags");
        if (tags == null) tags = new ArrayList<>();
        if (!tags.contains("imported")) tags.add("imported");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        String ifExists = paramString(params, "ifExists");
        if (ifExists == null) {
            ifExists = "reuse";
        } else {
            ifExists = ifExists.toLowerCase(java.util.Locale.ROOT);
            if (!VALID_IF_EXISTS.contains(ifExists)) {
                throw new ToolException("'ifExists' must be one of "
                        + VALID_IF_EXISTS + ", got '" + ifExists + "'");
            }
        }

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), project.getName(), path);
        if (existing.isPresent()) {
            DocumentDocument doc = existing.get();
            switch (ifExists) {
                case "reuse" -> {
                    log.info("DocImportUrl reuse tenant='{}' project='{}' path='{}' from='{}' id='{}'",
                            ctx.tenantId(), project.getName(), doc.getPath(), rawUrl, doc.getId());
                    return reuseResponse(doc, rawUrl);
                }
                case "error" -> throw new ToolException(
                        "Document '" + doc.getPath() + "' already exists in "
                                + ctx.tenantId() + "/" + project.getName()
                                + " — set ifExists='reuse' to use the existing "
                                + "doc, or ifExists='update' to overwrite it.");
                // 'update' falls through to fetch + replaceContent below.
                default -> { /* update — fall through */ }
            }
        }

        boolean insecure = paramBoolean(params, "insecure");
        byte[] body;
        String contentType;
        int status;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpClient client = insecure ? InsecureHttpClientFactory.client() : http;
            if (insecure) {
                log.warn("DocImportUrlTool tenant='{}' url='{}' — TLS verification disabled (insecure=true)",
                        ctx.tenantId(), rawUrl);
            }
            HttpResponse<byte[]> response;
            try {
                response = SsrfGuard.sendGuarded(
                        client, request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (SsrfGuard.SsrfException e) {
                throw new ToolException(e.getMessage());
            }
            status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new ToolException(
                        "Fetch returned HTTP " + status + " for " + rawUrl);
            }
            body = response.body() == null ? new byte[0] : response.body();
            if (body.length > MAX_IMPORT_BYTES) {
                throw new ToolException(
                        "Body too large (" + body.length + " bytes, limit "
                                + MAX_IMPORT_BYTES + "). Use a smaller "
                                + "source or summarise first.");
            }
            contentType = response.headers()
                    .firstValue("content-type").orElse("application/octet-stream");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted fetching '" + rawUrl + "'");
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DocImportUrlTool tenant='{}' url='{}' failed: {}",
                    ctx.tenantId(), rawUrl, e.toString());
            throw new ToolException("Fetch failed: " + e.getMessage(), e);
        }

        DocumentDocument result;
        boolean updated = false;
        if (existing.isPresent()) {
            // ifExists='update' — replace the body and mime-type on the
            // pre-existing doc. Title and tags stay as the user originally
            // imported them; an LLM-driven refresh shouldn't silently
            // rewrite human-curated metadata.
            result = documentService.replaceContent(
                    existing.get().getId(),
                    new ByteArrayInputStream(body),
                    contentType,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), existing.get().getPath()));
            updated = true;
        } else {
            try {
                result = documentService.create(
                        ctx.tenantId(),
                        project.getName(),
                        path,
                        title,
                        tags,
                        contentType,
                        new ByteArrayInputStream(body),
                        ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), path));
            } catch (DocumentService.DocumentAlreadyExistsException e) {
                // Race with a concurrent import on the same path. The
                // fetched body is discarded — falling back to reuse is
                // friendlier to the caller than failing the call, since
                // the LLM would otherwise spin on the same retry.
                DocumentDocument doc = documentService.findByPath(
                        ctx.tenantId(), project.getName(), path)
                        .orElseThrow(() -> new ToolException(e.getMessage(), e));
                log.info("DocImportUrl race-recovered tenant='{}' project='{}' path='{}' id='{}'",
                        ctx.tenantId(), project.getName(), doc.getPath(), doc.getId());
                return reuseResponse(doc, rawUrl);
            }
        }

        // Optional caller-supplied summary — important for binaries
        // (images, PDFs) where the auto-summary scheduler doesn't run.
        String summary = paramString(params, "summary");
        if (summary != null) {
            documentService.setSummary(result.getId(), summary,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), result.getPath()));
        }

        log.info("DocImportUrl {} tenant='{}' project='{}' path='{}' from='{}' bytes={} summary={}",
                updated ? "update" : "create",
                ctx.tenantId(), project.getName(), result.getPath(), rawUrl,
                body.length, summary != null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", result.getId());
        out.put("projectId", result.getProjectId());
        out.put("path", result.getPath());
        out.put("name", result.getName());
        if (result.getTitle() != null) out.put("title", result.getTitle());
        out.put("mimeType", result.getMimeType());
        out.put("size", result.getSize());
        out.put("tags", result.getTags() == null ? List.of() : result.getTags());
        if (summary != null) out.put("summary", summary);
        out.put("sourceUrl", rawUrl);
        out.put("httpStatus", status);
        if (updated) out.put("updated", true);
        return out;
    }

    private static Map<String, Object> reuseResponse(DocumentDocument doc, String rawUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("projectId", doc.getProjectId());
        out.put("path", doc.getPath());
        out.put("name", doc.getName());
        if (doc.getTitle() != null) out.put("title", doc.getTitle());
        if (doc.getMimeType() != null) out.put("mimeType", doc.getMimeType());
        out.put("size", doc.getSize());
        out.put("tags", doc.getTags() == null ? List.of() : doc.getTags());
        out.put("sourceUrl", rawUrl);
        out.put("reused", true);
        return out;
    }

    /**
     * Auto-place an imported doc under
     * {@link de.mhus.vance.shared.document.DocumentService#DOCUMENTS_FOLDER_PREFIX}
     * when the caller didn't supply a path. Prefers the URL's last
     * path segment so {@code https://example.com/notes/apollo-13}
     * becomes {@code documents/apollo-13}. Falls back to a title slug
     * when the URL has no useful path tail, and to a short UUID when
     * even that is empty.
     */
    static String autoPath(URI uri, @org.jspecify.annotations.Nullable String title) {
        String tail = "";
        String uriPath = uri.getPath();
        if (uriPath != null && !uriPath.isBlank()) {
            String trimmed = uriPath.endsWith("/")
                    ? uriPath.substring(0, uriPath.length() - 1)
                    : uriPath;
            int slash = trimmed.lastIndexOf('/');
            tail = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        }
        String slug = slugify(tail.isEmpty() ? title : tail);
        String filename = slug.isEmpty()
                ? java.util.UUID.randomUUID().toString().substring(0, 8)
                : slug;
        return de.mhus.vance.shared.document.DocumentService.DOCUMENTS_FOLDER_PREFIX
                + filename;
    }

    private static String slugify(@org.jspecify.annotations.Nullable String s) {
        if (s == null) return "";
        String slug = s.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9.]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.length() > 50 ? slug.substring(0, 50) : slug;
    }

    private static @org.jspecify.annotations.Nullable String paramString(
            Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBoolean(Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return false;
    }

    @SuppressWarnings("unchecked")
    private static @org.jspecify.annotations.Nullable List<String> paramStringList(
            Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (!(v instanceof List<?> raw)) return null;
        List<String> out = new ArrayList<>(raw.size());
        for (Object e : raw) {
            if (e instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out.isEmpty() ? null : out;
    }
}
