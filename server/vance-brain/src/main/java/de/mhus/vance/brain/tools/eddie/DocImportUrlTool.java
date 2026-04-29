package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
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
     * Mongo / storage afterwards, not in an LLM call. 2 MB covers
     * most articles plus PDFs of moderate size.
     */
    private static final int MAX_IMPORT_BYTES = 2 * 1024 * 1024;

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
                            "description", "Document path inside the project, "
                                    + "e.g. 'imports/apollo-13.html'. "
                                    + "Must be unique per project."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional human title (defaults to the URL)."),
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Optional tags. 'imported' is "
                                    + "added automatically.")),
            "required", List.of("url", "path"));

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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String rawUrl = paramString(params, "url");
        String path = paramString(params, "path");
        if (rawUrl == null) throw new ToolException("'url' is required");
        if (path == null) throw new ToolException("'path' is required");

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

        log.info("DocImportUrl tenant='{}' project='{}' path='{}' from='{}' bytes={}",
                ctx.tenantId(), project.getName(), path, rawUrl, body.length);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.getId());
        out.put("projectId", created.getProjectId());
        out.put("path", created.getPath());
        out.put("name", created.getName());
        if (created.getTitle() != null) out.put("title", created.getTitle());
        out.put("mimeType", created.getMimeType());
        out.put("size", created.getSize());
        out.put("tags", created.getTags() == null ? List.of() : created.getTags());
        out.put("sourceUrl", rawUrl);
        out.put("httpStatus", status);
        return out;
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
