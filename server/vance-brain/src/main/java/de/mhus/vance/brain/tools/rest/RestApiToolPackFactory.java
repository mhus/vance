package de.mhus.vance.brain.tools.rest;

import de.mhus.vance.brain.toolpack.core.PackHttpClient;
import de.mhus.vance.brain.toolpack.rest.OpenApiOperation;
import de.mhus.vance.brain.toolpack.rest.OpenApiSpecLoader;
import de.mhus.vance.brain.toolpack.rest.RestApiConfig;
import de.mhus.vance.brain.toolpack.rest.RestHttpInvoker;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Multi-tool pack factory for {@code type: rest_api}. Parses the
 * configured OpenAPI/Swagger spec at materialisation time and emits
 * one {@link RestEndpointTool} per operation that survives the
 * include/exclude filter. Tool names are
 * {@code <packName>__<operationId>}.
 *
 * <p>See {@code planning/server-tool-providers.md} §4.2 for the
 * recipe-side YAML schema.
 *
 * <p>Caching of the materialised tool list is the
 * {@link de.mhus.vance.brain.servertool.ServerToolService}'s job —
 * this factory is stateless and cheap to call. Spec parsing happens
 * inside {@link OpenApiSpecLoader}; the JDK HttpClient + TLS
 * settings come from {@link PackHttpClient} which is shared with
 * future MCP-pack types.
 */
@Component
@Slf4j
public class RestApiToolPackFactory implements ToolFactory {

    public static final String TYPE_ID = "rest_api";

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "specUrl", Map.of("type", "string",
                            "description", "URL to the OpenAPI/Swagger spec (HTTP(S))."),
                    "specInline", Map.of("type", "string",
                            "description", "Inline OpenAPI/Swagger spec body (alternative to specUrl)."),
                    "baseUrl", Map.of("type", "string",
                            "description", "Override the spec's servers[].url."),
                    "auth", Map.of("type", "object",
                            "description", "Authentication block — bearer/basic/apiKey/none."),
                    "tls", Map.of("type", "object",
                            "description", "TLS settings (skipVerification, trustedCaPemPath)."),
                    "include", Map.of("type", "array",
                            "description", "operationId-glob whitelist."),
                    "exclude", Map.of("type", "array",
                            "description", "operationId-glob blacklist (applied after include).")));

    private final PackHttpClient httpClient;
    private final SettingsSecretResolver secretResolver;

    public RestApiToolPackFactory(SettingsSecretResolver secretResolver) {
        this.httpClient = new PackHttpClient();
        this.secretResolver = secretResolver;
    }

    @Override public String typeId() { return TYPE_ID; }
    @Override public Map<String, Object> parametersSchema() { return PARAMETERS_SCHEMA; }

    @Override
    public Collection<Tool> create(ServerToolDocument document) {
        RestApiConfig config = RestApiConfig.fromParameters(document.getParameters());

        List<OpenApiOperation> operations;
        OpenAPI spec = null;
        if (config.specInline() != null) {
            spec = OpenApiSpecLoader.parseSpec(config.specInline());
            operations = OpenApiSpecLoader.parseInline(config.specInline());
        } else {
            operations = OpenApiSpecLoader.loadFromUrl(config.specUrl());
            // Spec reference for base-URL resolution: re-parse cheap
            // (swagger-parser caches HTTP fetch under the hood).
            // TODO if the cost matters, refactor loadFromUrl to also
            // return the OpenAPI object.
        }

        String baseUrl = OpenApiSpecLoader.pickBaseUrl(config.baseUrl(), spec);
        if ((baseUrl == null || baseUrl.isEmpty()) && config.baseUrl() != null) {
            baseUrl = config.baseUrl();
        }
        if (baseUrl == null) baseUrl = "";
        RestHttpInvoker invoker = new RestHttpInvoker(httpClient, config, baseUrl, secretResolver);

        List<Pattern> includePatterns = compileGlobs(config.include());
        List<Pattern> excludePatterns = compileGlobs(config.exclude());
        Set<String> docLabels = document.getLabels() == null
                ? Set.of() : new LinkedHashSet<>(document.getLabels());
        boolean defaultDeferred = document.isDefaultDeferred();

        List<Tool> out = new ArrayList<>(operations.size());
        for (OpenApiOperation op : operations) {
            if (!matches(op.operationId(), includePatterns, excludePatterns)) continue;
            String fullName = document.getName() + ToolFactory.PACK_SEPARATOR + op.operationId();
            Set<String> labels = mergeLabels(docLabels, config, op);
            boolean deferred = config.deferredOverrides().getOrDefault(op.operationId(), defaultDeferred);
            String desc = pickDescription(op);
            String hint = op.summary() != null && !op.summary().isBlank()
                    ? op.summary() : desc;
            out.add(new RestEndpointTool(
                    fullName, desc, labels, deferred, document.isPrimary(), hint, op, invoker));
        }
        log.info("RestApiToolPackFactory pack='{}' tenant='{}' project='{}' produced {} tools",
                document.getName(), document.getTenantId(), document.getProjectId(), out.size());
        return List.copyOf(out);
    }

    private static Set<String> mergeLabels(
            Set<String> packLabels,
            RestApiConfig config,
            OpenApiOperation op) {
        // Per-op override wins over per-method default; doc-pack labels
        // are always added on top so recipe selectors like @rest:jira
        // can still find every endpoint.
        Set<String> out = new LinkedHashSet<>();
        out.addAll(packLabels);
        out.add("rest");
        out.addAll(config.labelOverrides().getOrDefault(
                op.operationId(), config.labelsForMethod(op.httpMethod())));
        return Set.copyOf(out);
    }

    private static boolean matches(
            String operationId,
            List<Pattern> includes,
            List<Pattern> excludes) {
        boolean included = includes.isEmpty() || anyMatch(operationId, includes);
        if (!included) return false;
        return !anyMatch(operationId, excludes);
    }

    private static boolean anyMatch(String name, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) return true;
        }
        return false;
    }

    private static List<Pattern> compileGlobs(List<String> globs) {
        if (globs == null || globs.isEmpty()) return List.of();
        List<Pattern> out = new ArrayList<>(globs.size());
        for (String g : globs) {
            out.add(globToPattern(g));
        }
        return out;
    }

    /** Converts a {@code *}-glob to a regex; full-match semantics. */
    private static Pattern globToPattern(String glob) {
        StringBuilder rx = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> rx.append(".*");
                case '?' -> rx.append('.');
                case '.', '+', '(', ')', '[', ']', '{', '}', '|', '\\', '^', '$' -> rx.append('\\').append(c);
                default -> rx.append(c);
            }
        }
        rx.append('$');
        return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String pickDescription(OpenApiOperation op) {
        if (op.summary() != null && !op.summary().isBlank()) return op.summary();
        if (op.description() != null && !op.description().isBlank()) return op.description();
        return op.httpMethod().toUpperCase(Locale.ROOT) + " " + op.pathTemplate();
    }

    /**
     * Map<String,Object> overload for the static parameter-schema
     * field — keeps the @Override-able method body minimal. Using
     * LinkedHashMap so the keys serialise in declaration order when
     * an admin UI renders the schema.
     */
    @SuppressWarnings("unused")
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
