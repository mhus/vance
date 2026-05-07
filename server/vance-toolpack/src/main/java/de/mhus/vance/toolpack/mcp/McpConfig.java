package de.mhus.vance.toolpack.mcp;

import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.rest.AuthSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed view of an {@code mcp_server}-pack
 * {@code ServerToolDocument.parameters} block.
 *
 * <p>YAML schema:
 * <pre>
 *   parameters:
 *     transport: stdio | http        # required
 *
 *     # stdio:
 *     command: ["npx", "@modelcontextprotocol/server-filesystem", "/tmp/workspace"]
 *     cwd: "/var/lib/vance/mcp-fs"   # optional
 *     env:
 *       LOG_LEVEL: "info"
 *
 *     # http (Streamable HTTP, MCP 2025-spec):
 *     url: "https://mcp.example.com/mcp"
 *     # OR legacy HTTP+SSE (split endpoint):
 *     # postUrl: "https://mcp.example.com/messages"
 *     # sseUrl:  "https://mcp.example.com/sse"
 *     auth: { type: bearer, token: "{{secret:mcp.token}}" }
 *     tls:  { skipVerification: false }
 *
 *     timeoutSeconds: 60
 *     initTimeoutSeconds: 5
 * </pre>
 */
public record McpConfig(
        Transport transport,
        // stdio:
        List<String> command,
        @Nullable String cwd,
        Map<String, String> env,
        // http:
        @Nullable String url,
        @Nullable String postUrl,
        @Nullable String sseUrl,
        AuthSpec auth,
        PackHttpClient.TlsConfig tls,
        // common:
        int timeoutSeconds,
        int initTimeoutSeconds) {

    public enum Transport { STDIO, HTTP }

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_INIT_TIMEOUT_SECONDS = 5;

    @SuppressWarnings("unchecked")
    public static McpConfig fromParameters(@Nullable Map<String, Object> params) {
        if (params == null) params = Map.of();
        Transport t = parseTransport(params.get("transport"));

        List<String> command = stringList(params.get("command"));
        String cwd = stringOrNull(params.get("cwd"));
        Map<String, String> env = new LinkedHashMap<>();
        Object envRaw = params.get("env");
        if (envRaw instanceof Map<?, ?> em) {
            for (Map.Entry<?, ?> e : em.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    env.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }

        String url = stringOrNull(params.get("url"));
        String postUrl = stringOrNull(params.get("postUrl"));
        String sseUrl = stringOrNull(params.get("sseUrl"));

        AuthSpec auth = AuthSpec.fromMap(asMap(params.get("auth")));
        PackHttpClient.TlsConfig tls = PackHttpClient.TlsConfig.fromMap(asMap(params.get("tls")));

        int timeout = intOrDefault(params.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS);
        int initTimeout = intOrDefault(params.get("initTimeoutSeconds"), DEFAULT_INIT_TIMEOUT_SECONDS);

        validate(t, command, url, postUrl, sseUrl);

        return new McpConfig(
                t, command, cwd, Map.copyOf(env),
                url, postUrl, sseUrl,
                auth, tls,
                timeout, initTimeout);
    }

    private static void validate(
            Transport t, List<String> command,
            @Nullable String url, @Nullable String postUrl, @Nullable String sseUrl) {
        switch (t) {
            case STDIO -> {
                if (command == null || command.isEmpty()) {
                    throw new IllegalArgumentException(
                            "mcp_server: 'command' is required for transport=stdio");
                }
            }
            case HTTP -> {
                boolean hasSingle = url != null && !url.isBlank();
                boolean hasSplit = postUrl != null && sseUrl != null;
                if (!hasSingle && !hasSplit) {
                    throw new IllegalArgumentException(
                            "mcp_server: either 'url' (Streamable HTTP) or both "
                                    + "'postUrl' and 'sseUrl' (legacy HTTP+SSE) is required");
                }
                if (hasSingle && hasSplit) {
                    throw new IllegalArgumentException(
                            "mcp_server: pick either 'url' or ('postUrl' + 'sseUrl'), not both");
                }
            }
        }
    }

    private static Transport parseTransport(@Nullable Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "mcp_server: 'transport' is required (stdio | http)");
        }
        return switch (s.trim().toLowerCase()) {
            case "stdio" -> Transport.STDIO;
            case "http", "streamable-http", "http+sse" -> Transport.HTTP;
            default -> throw new IllegalArgumentException(
                    "mcp_server: unknown transport '" + s + "' (expected stdio | http)");
        };
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static List<String> stringList(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof String s) return s.isBlank() ? List.of() : List.of(s);
        if (!(raw instanceof List<?> list)) return List.of();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) out.add(s);
        }
        return List.copyOf(out);
    }

    private static int intOrDefault(@Nullable Object raw, int def) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ignored) { return def; }
        }
        return def;
    }
}
