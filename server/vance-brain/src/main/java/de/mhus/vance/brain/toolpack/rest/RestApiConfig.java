package de.mhus.vance.brain.toolpack.rest;

import de.mhus.vance.brain.toolpack.core.PackHttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed view of a {@code rest_api}-pack {@code ServerToolDocument.parameters}
 * block. Built once per doc-update by {@link RestApiToolPackFactory}; cheap
 * enough to discard and rebuild on cache miss.
 *
 * <p>YAML schema:
 * <pre>
 *   parameters:
 *     specUrl: "https://api.example.com/openapi.json"
 *     # or specInline: |
 *     #   { "openapi": "3.0.0", ... }
 *     baseUrl: "https://api.example.com"          # optional override of spec server
 *     auth:                                        # see AuthSpec
 *       type: bearer
 *       token: "{{secret:example.token}}"
 *     tls:                                         # see PackHttpClient.TlsConfig
 *       skipVerification: false
 *       trustedCaPemPath: "/etc/vance/example-ca.pem"
 *     include: ["issue.*", "search.*"]             # operationId glob, default: all
 *     exclude: ["admin_*"]
 *     methodLabels:                                # default mapping per HTTP verb
 *       GET:    [read-only]
 *       POST:   [write, side-effect]
 *       PUT:    [write, side-effect]
 *       PATCH:  [write, side-effect]
 *       DELETE: [write, side-effect]
 *     labelOverrides:                              # per-operationId override
 *       getServerInfo: [read-only]
 *     deferredOverrides:                           # per-operationId Tool.deferred()
 *       createIssue: false
 *     timeoutSeconds: 30
 * </pre>
 *
 * <p>Pure Java — no Spring, no Vance internals. The
 * {@code {{secret:...}}} references in {@link #auth()} are
 * resolved at invoke-time, not at config-build-time, so config
 * objects are safe to log / cache.
 */
public record RestApiConfig(
        @Nullable String specUrl,
        @Nullable String specInline,
        @Nullable String baseUrl,
        AuthSpec auth,
        PackHttpClient.TlsConfig tls,
        List<String> include,
        List<String> exclude,
        Map<String, List<String>> methodLabels,
        Map<String, List<String>> labelOverrides,
        Map<String, Boolean> deferredOverrides,
        int timeoutSeconds) {

    private static final Map<String, List<String>> DEFAULT_METHOD_LABELS = Map.of(
            "GET", List.of("read-only"),
            "POST", List.of("write", "side-effect"),
            "PUT", List.of("write", "side-effect"),
            "PATCH", List.of("write", "side-effect"),
            "DELETE", List.of("write", "side-effect"),
            "HEAD", List.of("read-only"),
            "OPTIONS", List.of("read-only"));

    /** Default request timeout when {@code timeoutSeconds} is not set. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @SuppressWarnings("unchecked")
    public static RestApiConfig fromParameters(@Nullable Map<String, Object> params) {
        if (params == null) params = Map.of();
        String specUrl = stringOrNull(params.get("specUrl"));
        String specInline = stringOrNull(params.get("specInline"));
        if (specUrl == null && specInline == null) {
            throw new IllegalArgumentException(
                    "rest_api: 'specUrl' or 'specInline' is required");
        }
        String baseUrl = stringOrNull(params.get("baseUrl"));

        AuthSpec auth = AuthSpec.fromMap(asMap(params.get("auth")));
        PackHttpClient.TlsConfig tls = PackHttpClient.TlsConfig.fromMap(asMap(params.get("tls")));

        List<String> include = stringList(params.get("include"));
        List<String> exclude = stringList(params.get("exclude"));

        Map<String, List<String>> methodLabels = new LinkedHashMap<>(DEFAULT_METHOD_LABELS);
        Map<String, Object> mlRaw = asMap(params.get("methodLabels"));
        if (mlRaw != null) {
            for (Map.Entry<String, Object> e : mlRaw.entrySet()) {
                methodLabels.put(e.getKey().toUpperCase(), stringList(e.getValue()));
            }
        }

        Map<String, List<String>> labelOverrides = new LinkedHashMap<>();
        Map<String, Object> loRaw = asMap(params.get("labelOverrides"));
        if (loRaw != null) {
            for (Map.Entry<String, Object> e : loRaw.entrySet()) {
                labelOverrides.put(e.getKey(), stringList(e.getValue()));
            }
        }

        Map<String, Boolean> deferredOverrides = new LinkedHashMap<>();
        Map<String, Object> doRaw = asMap(params.get("deferredOverrides"));
        if (doRaw != null) {
            for (Map.Entry<String, Object> e : doRaw.entrySet()) {
                if (e.getValue() instanceof Boolean b) deferredOverrides.put(e.getKey(), b);
            }
        }

        int timeout = DEFAULT_TIMEOUT_SECONDS;
        Object tsRaw = params.get("timeoutSeconds");
        if (tsRaw instanceof Number n) timeout = n.intValue();
        else if (tsRaw instanceof String s && !s.isBlank()) {
            try { timeout = Integer.parseInt(s.trim()); }
            catch (NumberFormatException ignored) { /* keep default */ }
        }

        return new RestApiConfig(
                specUrl, specInline, baseUrl,
                auth, tls,
                include, exclude,
                Map.copyOf(methodLabels),
                Map.copyOf(labelOverrides),
                Map.copyOf(deferredOverrides),
                timeout);
    }

    /**
     * Default labels for an HTTP method — falls back to
     * {@code [side-effect]} when neither the recipe nor the default
     * map carries the verb.
     */
    public List<String> labelsForMethod(String httpMethod) {
        if (httpMethod == null) return List.of("side-effect");
        List<String> hit = methodLabels.get(httpMethod.toUpperCase());
        return hit == null ? List.of("side-effect") : hit;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static List<String> stringList(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof String s) return s.isBlank() ? List.of() : List.of(s);
        if (!(raw instanceof List<?> list)) return List.of();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return List.copyOf(out);
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
