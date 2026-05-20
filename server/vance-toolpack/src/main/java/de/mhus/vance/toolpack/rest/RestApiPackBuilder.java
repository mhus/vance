package de.mhus.vance.toolpack.rest;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.core.SecretResolver;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-Java orchestrator that turns a REST-API pack specification
 * (parameters + meta) into a list of runnable {@link Tool}s. Lives in
 * vance-toolpack so both server and foot can use it: server-side
 * {@code RestApiToolPackFactory} reads a {@code ServerToolDocument};
 * foot-side {@code FootToolPackRegistry} reads a JSON file. Both
 * eventually call {@link #build(PackInput, PackHttpClient, SecretResolver)}.
 *
 * <p>Tool names follow the {@code <packName>__<operationId>} convention.
 * Labels: pack-doc labels + per-method default + per-operationId
 * override. Deferred: per-operationId override falls back to pack
 * {@code defaultDeferred}.
 */
public final class RestApiPackBuilder {

    private RestApiPackBuilder() { /* static only */ }

    /**
     * Pack input: name + labels + primary/deferred flags + raw
     * parameters map. Exact JSON-shape of the parameters block is
     * decoded by {@link RestApiConfig}; this struct just carries the
     * outer attributes that live alongside the parameters in the
     * source document (Mongo or JSON file).
     */
    public record PackInput(
            String name,
            Set<String> labels,
            boolean primary,
            boolean defaultDeferred,
            Map<String, Object> parameters) {
    }

    /**
     * Materialises the pack. Network-bound — fetches the OpenAPI spec
     * via {@link OpenApiSpecLoader} and resolves the base-URL. The
     * returned {@link RestEndpointTool}s share one {@link RestHttpInvoker}
     * (and therefore one {@link PackHttpClient} entry) so they pool
     * connections.
     */
    public static Collection<Tool> build(
            PackInput input,
            PackHttpClient httpClient,
            SecretResolver secretResolver) {

        RestApiConfig config = RestApiConfig.fromParameters(input.parameters());

        List<OpenApiOperation> operations;
        OpenAPI spec = null;
        if (config.specInline() != null) {
            spec = OpenApiSpecLoader.parseSpec(config.specInline());
            operations = OpenApiSpecLoader.parseInline(config.specInline());
        } else {
            OpenApiSpecLoader.LoadResult loaded = OpenApiSpecLoader.loadFromUrl(config.specUrl());
            spec = loaded.spec();
            operations = loaded.operations();
        }

        String baseUrl = OpenApiSpecLoader.pickBaseUrl(config.baseUrl(), spec);
        if ((baseUrl == null || baseUrl.isEmpty()) && config.baseUrl() != null) {
            baseUrl = config.baseUrl();
        }
        if (baseUrl == null) baseUrl = "";
        RestHttpInvoker invoker = new RestHttpInvoker(httpClient, config, baseUrl, secretResolver);

        List<Pattern> includePatterns = compileGlobs(config.include());
        List<Pattern> excludePatterns = compileGlobs(config.exclude());
        Set<String> packLabels = input.labels() == null ? Set.of() : input.labels();

        List<Tool> out = new ArrayList<>(operations.size());
        for (OpenApiOperation op : operations) {
            if (!matches(op.operationId(), includePatterns, excludePatterns)) continue;
            // LLM tool-name conventions reject dots (Anthropic, OpenAI both
            // require ^[a-zA-Z0-9_-]+$). Google's APIs use dotted operationIds
            // (e.g. gmail.users.messages.list); map them to underscored form
            // for the tool name only. Labels/deferred-overrides keep matching
            // against the raw operationId so include-globs can still use
            // either dot or underscore form per the user's preference.
            String fullName = input.name() + "__" + toolSafeName(op.operationId());
            Set<String> labels = mergeLabels(packLabels, config, op);
            boolean deferred = config.deferredOverrides().getOrDefault(
                    op.operationId(), input.defaultDeferred());
            String desc = pickDescription(op);
            String hint = op.summary() != null && !op.summary().isBlank()
                    ? op.summary() : desc;
            out.add(new RestEndpointTool(
                    fullName, desc, labels, deferred, input.primary(), hint, op, invoker));
        }
        return List.copyOf(out);
    }

    // ─────── internals ───────

    private static Set<String> mergeLabels(
            Set<String> packLabels,
            RestApiConfig config,
            OpenApiOperation op) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(packLabels);
        out.add("rest");
        out.addAll(config.labelOverrides().getOrDefault(
                op.operationId(), config.labelsForMethod(op.httpMethod())));
        return Set.copyOf(out);
    }

    /**
     * Strip characters disallowed in LLM tool names (Anthropic / OpenAI
     * both insist on {@code ^[a-zA-Z0-9_-]+$}). Currently just dot →
     * underscore — broaden if a spec ever uses other oddities.
     */
    static String toolSafeName(String operationId) {
        if (operationId == null || operationId.isEmpty()) return operationId;
        return operationId.replace('.', '_');
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
}
