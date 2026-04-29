package de.mhus.vance.aitest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thin REST client for the brain's Insights admin endpoints.
 * Authenticated via JWT minted by {@link BrainAuthClient}.
 *
 * <p>Used by the long-running ai-tests to peek at engine state without
 * going through Mongo directly — gives a coherent server-side view of
 * what's running, what's settled, and what's stuck.
 */
public final class BrainInsightsClient {

    private final BrainAuthClient auth;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public BrainInsightsClient(BrainAuthClient auth) {
        this.auth = auth;
    }

    /**
     * GET {@code /brain/{tenant}/admin/sessions/{sessionId}/processes}.
     * Returns the list of {@code ThinkProcessInsightsDto}s as raw maps —
     * we only read a handful of fields (id, name, thinkEngine, status,
     * parentProcessId, recipeName), so a typed DTO is not worth the deps.
     */
    public List<Map<String, Object>> processesForSession(String sessionId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(
                                auth.baseUrl() + "/brain/" + auth.tenant()
                                        + "/admin/sessions/" + sessionId + "/processes"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + auth.token())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("/admin/sessions/.../processes returned HTTP "
                    + r.statusCode() + ": " + r.body());
        }
        Object body = json.readValue(r.body(), Object.class);
        if (!(body instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                out.add(casted);
            }
        }
        return out;
    }

    /**
     * Pretty one-liner per process — engine/status/recipe — with parent
     * indentation. Returned as a String so callers can dump it into
     * test-fail messages without per-row plumbing.
     */
    public String formatProcessTree(List<Map<String, Object>> processes) {
        StringBuilder sb = new StringBuilder();
        sb.append("processes (").append(processes.size()).append("):\n");
        for (Map<String, Object> p : processes) {
            String parent = String.valueOf(p.getOrDefault("parentProcessId", ""));
            String indent = parent.isEmpty() || "null".equals(parent) ? "  " : "    └─ ";
            sb.append(indent)
                    .append(p.get("name")).append(' ')
                    .append("[engine=").append(p.get("thinkEngine"))
                    .append(", recipe=").append(p.get("recipeName"))
                    .append(", status=").append(p.get("status"))
                    .append(", id=").append(p.get("id"))
                    .append("]\n");
        }
        return sb.toString();
    }
}
