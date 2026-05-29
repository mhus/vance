package de.mhus.vance.brain.tools.document;

import de.mhus.vance.brain.tools.eddie.EddieContext;
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
                                    + "is set in the manifest.")),
            "required", List.of("url"));

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    public DocImportUrlTool(EddieContext eddieContext, DocumentService documentService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
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
                + "reads use web_fetch instead.";
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
            HttpResponse<byte[]> response = http.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
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

        DocumentDocument created;
        try {
            created = documentService.create(
                    ctx.tenantId(),
                    project.getName(),
                    path,
                    title,
                    tags,
                    contentType,
                    new ByteArrayInputStream(body),
                    ctx.userId());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }

        // Optional caller-supplied summary — important for binaries
        // (images, PDFs) where the auto-summary scheduler doesn't run.
        String summary = paramString(params, "summary");
        if (summary != null) {
            documentService.setSummary(created.getId(), summary);
        }

        log.info("DocImportUrl tenant='{}' project='{}' path='{}' from='{}' bytes={} summary={}",
                ctx.tenantId(), project.getName(), path, rawUrl, body.length,
                summary != null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.getId());
        out.put("projectId", created.getProjectId());
        out.put("path", created.getPath());
        out.put("name", created.getName());
        if (created.getTitle() != null) out.put("title", created.getTitle());
        out.put("mimeType", created.getMimeType());
        out.put("size", created.getSize());
        out.put("tags", created.getTags() == null ? List.of() : created.getTags());
        if (summary != null) out.put("summary", summary);
        out.put("sourceUrl", rawUrl);
        out.put("httpStatus", status);
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
