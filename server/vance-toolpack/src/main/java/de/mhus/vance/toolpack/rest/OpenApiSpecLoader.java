package de.mhus.vance.toolpack.rest;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses an OpenAPI 3.x or Swagger 2.x specification into a list of
 * {@link OpenApiOperation}s. Pure Java — wraps swagger-parser-v3.
 *
 * <p>Two intake modes:
 * <ul>
 *   <li>{@link #loadFromUrl} — fetches from {@code http(s)://}, reads
 *       JSON or YAML automatically (parser sniffs).</li>
 *   <li>{@link #parseInline} — accepts the spec body as a string.</li>
 * </ul>
 *
 * <p>{@link #pickBaseUrl} resolves the effective base URL: caller
 * override (recipe-pinned) wins; otherwise the spec's first
 * {@code servers[].url}; otherwise the empty string (operations have
 * fully-qualified paths somehow).
 *
 * <p>Refs ({@code $ref}) are inlined by the parser when
 * {@code resolveFully} is true — the resulting schemas are
 * self-contained, which Vance's {@link Tool#paramsSchema()} requires.
 */
public final class OpenApiSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSpecLoader.class);

    private OpenApiSpecLoader() { /* static */ }

    /**
     * Result of a URL-driven load: the raw {@link OpenAPI} (for
     * {@code servers[].url} extraction) plus the parsed operations.
     */
    public record LoadResult(@Nullable OpenAPI spec, List<OpenApiOperation> operations) {}

    /**
     * Process-wide cache of parsed specs. {@link #loadFromUrl} is keyed
     * by URL, {@link #loadInline} (and the {@link #parseInline} /
     * {@link #parseSpec} facades that delegate to it) is keyed by the
     * raw body text. Same URL or same body in → same {@link LoadResult}
     * out, no re-fetch and no re-parse.
     *
     * <p>Lives for the JVM lifetime; OpenAPI specs are effectively
     * immutable once published (Google freezes Discovery versions,
     * vendor specs are tagged). When a kit edit or OAuth-disconnect
     * needs to drop the cache, call {@link #clearCache()}.
     *
     * <p>Without this cache every {@code factory.create(doc, ctx)} on
     * a REST pack re-downloads + re-parses the spec — observed at 100+
     * rebuilds per chat-session against Google's Gmail Discovery URL.
     */
    private static final ConcurrentMap<String, LoadResult> URL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, LoadResult> INLINE_CACHE = new ConcurrentHashMap<>();

    /**
     * Default: HTTP(S) fetch and full ref-inlining. Cached by URL —
     * second and subsequent calls for the same URL skip the network
     * and the parser. Returns both the raw OpenAPI (so the caller can
     * pull {@code servers[].url} via {@link #pickBaseUrl}) and the
     * extracted operations.
     */
    public static LoadResult loadFromUrl(String url) {
        return URL_CACHE.computeIfAbsent(url, OpenApiSpecLoader::doLoadFromUrl);
    }

    private static LoadResult doLoadFromUrl(String url) {
        log.info("OpenApiSpecLoader: fetching + parsing spec from {}", url);
        ParseOptions opt = new ParseOptions();
        opt.setResolveFully(true);
        opt.setResolve(true);
        SwaggerParseResult result = new OpenAPIParser().readLocation(url, null, opt);
        OpenAPI spec = result == null ? null : result.getOpenAPI();
        return new LoadResult(spec, extractOperations(result, "url=" + url));
    }

    /**
     * Inline-spec variant. Cached by body content — same string in,
     * same {@link LoadResult} out. Heavier consumers should prefer
     * this over {@link #parseInline} + {@link #parseSpec} (which both
     * delegate here, but a single call avoids the rare race where the
     * cache is cleared between the two facades).
     */
    public static LoadResult loadInline(String specBody) {
        return INLINE_CACHE.computeIfAbsent(specBody, OpenApiSpecLoader::doLoadInline);
    }

    private static LoadResult doLoadInline(String specBody) {
        log.debug("OpenApiSpecLoader: parsing inline spec ({} chars)", specBody.length());
        ParseOptions opt = new ParseOptions();
        opt.setResolveFully(true);
        opt.setResolve(true);
        SwaggerParseResult result = new OpenAPIParser().readContents(specBody, null, opt);
        OpenAPI spec = result == null ? null : result.getOpenAPI();
        return new LoadResult(spec, extractOperations(result, "inline"));
    }

    /** Inline-spec variant — operations only. Backed by {@link #loadInline}. */
    public static List<OpenApiOperation> parseInline(String specBody) {
        return loadInline(specBody).operations();
    }

    /**
     * Picks the effective base-URL: explicit override wins, otherwise
     * the first {@code servers[].url} from the spec. Empty string when
     * neither exists (callers must then ship absolute paths in
     * {@link OpenApiOperation#pathTemplate()}).
     */
    public static String pickBaseUrl(@Nullable String override, @Nullable OpenAPI spec) {
        if (override != null && !override.isBlank()) return stripTrailingSlash(override.trim());
        if (spec == null) return "";
        List<Server> servers = spec.getServers();
        if (servers == null || servers.isEmpty()) return "";
        Server first = servers.get(0);
        if (first == null || first.getUrl() == null) return "";
        return stripTrailingSlash(first.getUrl().trim());
    }

    /** Variant used by tests and packs that already have the spec body. Backed by {@link #loadInline}. */
    public static @Nullable OpenAPI parseSpec(String body) {
        return loadInline(body).spec();
    }

    /**
     * Drops every cached {@link LoadResult}. Call sites: tests (so one
     * test's parse doesn't leak into the next), kit-edit handlers that
     * change a {@code specUrl} or {@code specInline} body, OAuth-
     * disconnect events that invalidate auth-conditional schemas.
     * Cheap — just two map clears.
     */
    public static void clearCache() {
        URL_CACHE.clear();
        INLINE_CACHE.clear();
    }

    private static List<OpenApiOperation> extractOperations(SwaggerParseResult result, String origin) {
        if (result == null || result.getOpenAPI() == null) {
            String msg = result == null || result.getMessages() == null
                    ? "no OpenAPI returned"
                    : String.join(", ", result.getMessages());
            throw new IllegalArgumentException(
                    "OpenAPI spec failed to parse (" + origin + "): " + msg);
        }
        OpenAPI api = result.getOpenAPI();
        if (api.getPaths() == null || api.getPaths().isEmpty()) {
            return List.of();
        }
        List<OpenApiOperation> out = new ArrayList<>();
        for (Map.Entry<String, PathItem> path : api.getPaths().entrySet()) {
            String pathTemplate = path.getKey();
            PathItem item = path.getValue();
            forEachOp(item, (method, op) -> {
                if (op == null) return;
                out.add(toOperation(method, pathTemplate, op));
            });
        }
        return List.copyOf(out);
    }

    private static void forEachOp(PathItem item, java.util.function.BiConsumer<String, Operation> visit) {
        visit.accept("GET", item.getGet());
        visit.accept("POST", item.getPost());
        visit.accept("PUT", item.getPut());
        visit.accept("PATCH", item.getPatch());
        visit.accept("DELETE", item.getDelete());
        visit.accept("HEAD", item.getHead());
        visit.accept("OPTIONS", item.getOptions());
    }

    private static OpenApiOperation toOperation(String method, String pathTemplate, Operation op) {
        String operationId = op.getOperationId();
        if (operationId == null || operationId.isBlank()) {
            operationId = synthesizeOperationId(method, pathTemplate);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<String> pathParamNames = new ArrayList<>();
        List<String> queryParamNames = new ArrayList<>();
        List<String> headerParamNames = new ArrayList<>();

        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                if (p == null || p.getName() == null) continue;
                String in = p.getIn() == null ? "query" : p.getIn();
                Map<String, Object> propSchema = schemaToMap(p.getSchema(), p.getDescription());
                properties.put(p.getName(), propSchema);
                if (Boolean.TRUE.equals(p.getRequired())) required.add(p.getName());
                switch (in) {
                    case "path" -> pathParamNames.add(p.getName());
                    case "header" -> headerParamNames.add(p.getName());
                    default -> queryParamNames.add(p.getName());
                }
            }
        }

        String bodyParamName = null;
        String bodyContentType = null;
        RequestBody body = op.getRequestBody();
        if (body != null && body.getContent() != null && !body.getContent().isEmpty()) {
            Map.Entry<String, MediaType> first = pickPreferredMedia(body.getContent());
            bodyContentType = first.getKey();
            MediaType mt = first.getValue();
            Schema<?> schema = mt == null ? null : mt.getSchema();
            if (schema != null) {
                bodyParamName = "body";
                properties.put(bodyParamName, schemaToMap(schema, "Request body."));
                if (Boolean.TRUE.equals(body.getRequired())) required.add(bodyParamName);
            }
        }

        Map<String, Object> paramsSchema = new LinkedHashMap<>();
        paramsSchema.put("type", "object");
        paramsSchema.put("properties", properties);
        if (!required.isEmpty()) paramsSchema.put("required", required);

        return new OpenApiOperation(
                operationId, method, pathTemplate,
                op.getSummary(), op.getDescription(),
                paramsSchema, pathParamNames, queryParamNames, headerParamNames,
                bodyParamName, bodyContentType);
    }

    /**
     * Prefers JSON over other media types; falls back to the first
     * registered one. APIs that ship JSON+XML get JSON; APIs that only
     * ship XML get the XML media-type echoed back so the caller can at
     * least set the right Content-Type header.
     */
    private static Map.Entry<String, MediaType> pickPreferredMedia(Content content) {
        Map.Entry<String, MediaType> json = null;
        Map.Entry<String, MediaType> first = null;
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            if (first == null) first = e;
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase();
            if (key.contains("json")) { json = e; break; }
        }
        return json != null ? json : first;
    }

    private static Map<String, Object> schemaToMap(@Nullable Schema<?> schema, @Nullable String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema == null) {
            out.put("type", "string");
            if (description != null) out.put("description", description);
            return out;
        }
        if (schema.getType() != null) out.put("type", schema.getType());
        else out.put("type", "string");
        if (description != null && !description.isBlank()) out.put("description", description);
        else if (schema.getDescription() != null) out.put("description", schema.getDescription());
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            out.put("enum", new ArrayList<>(schema.getEnum()));
        }
        if (schema.getFormat() != null) out.put("format", schema.getFormat());
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<String, Schema> e : schema.getProperties().entrySet()) {
                nested.put(e.getKey(), schemaToMap(e.getValue(), null));
            }
            out.put("properties", nested);
        }
        if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
            out.put("required", new ArrayList<>(schema.getRequired()));
        }
        if (schema.getItems() != null) {
            out.put("items", schemaToMap(schema.getItems(), null));
        }
        return out;
    }

    private static String synthesizeOperationId(String method, String path) {
        StringBuilder sb = new StringBuilder(method.toLowerCase());
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            String clean = seg.replace("{", "").replace("}", "").replaceAll("[^A-Za-z0-9_]", "_");
            if (clean.isEmpty()) continue;
            sb.append(Character.toUpperCase(clean.charAt(0))).append(clean.substring(1));
        }
        return sb.toString();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
