package de.mhus.vance.brain.cluster.placement;

import de.mhus.vance.brain.cluster.ClusterProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends the placement demand to the configured URL.
 *
 * <p><b>A push here is nothing but an outbound HTTP request carrying the same
 * body {@code GET /internal/cluster/placement/demand} returns.</b> No protocol,
 * no subscription, no acknowledgement beyond the HTTP status — the direction of
 * the call is reversed, and that is the whole difference
 * ({@code planning/project-placement-labels.md} §6.4).
 *
 * <p>Method is {@code POST}. {@code PUT} would be defensible — the payload is a
 * complete state that <em>replaces</em> what the receiver had, not an addition —
 * but every webhook receiver expects POST, and the idempotence follows from the
 * body rather than from the verb.
 *
 * <p>Its own client, per the rule that outbound REST to a foreign system goes
 * through a dedicated {@code *Client} service, and deliberately <b>not</b>
 * {@code HttpClusterBringClient}: that one signs with the {@code _vance-cluster}
 * internal token for pod-to-pod hops, and this call leaves the cluster.
 *
 * <p><b>No SSRF gate.</b> The URL is operator configuration, not user input.
 * {@code SsrfGuard} answers "may <em>we</em> fetch this" and rejects intranet
 * hosts — which is exactly where an in-cluster provisioner lives. Only the
 * scheme is checked.
 */
@Service
@Slf4j
public class PlacementWebhookClient {

    /**
     * Placeholder values go into a URL path, so a tenant id that slipped
     * through would be a path change at the receiver. Rejected with a warning
     * rather than substituted.
     */
    private static final Pattern SAFE_PLACEHOLDER = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final ClusterProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PlacementWebhookClient(ClusterProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getPlacement().getWebhookTimeout())
                .build();
    }

    /** {@code true} when a target URL is configured at all. */
    public boolean isConfigured() {
        String url = properties.getPlacement().getWebhookUrl();
        return url != null && !url.isBlank();
    }

    /**
     * Posts {@code demand}. Returns the outcome so the caller can decide what to
     * remember; never throws — a webhook is a report, and failing to deliver a
     * report must not break the round that produced it.
     *
     * @param tenantId substituted for {@code {tenant}}; {@code null} for the
     *     cluster-wide push, which is only valid when the URL has no
     *     {@code {tenant}} placeholder
     */
    public Result send(PlacementDemand demand, @Nullable String tenantId) {
        String url = resolveUrl(tenantId);
        if (url == null) {
            return new Result(false, 0, "url not usable");
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(demand);
        } catch (RuntimeException e) {
            log.warn("Placement webhook: could not serialise demand: {}", e.toString());
            return new Result(false, 0, "serialisation failed");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(properties.getPlacement().getWebhookTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        String token = properties.getPlacement().getWebhookToken();
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                // 2xx may carry {eta, note} for a human. Unknown fields are
                // ignored — the response is information, never an instruction to
                // the placement layer.
                return new Result(true, status, trimForLog(response.body()));
            }
            // A refusal is worth more than silence: "no pod will come" (quota,
            // budget, unknown selector) is a different situation from "one is on
            // its way", and without this line the two look identical.
            log.warn("Placement webhook refused by '{}': status={} body={}",
                    url, status, trimForLog(response.body()));
            return new Result(false, status, trimForLog(response.body()));
        } catch (java.io.IOException e) {
            log.warn("Placement webhook to '{}' failed: {}", url, e.toString());
            return new Result(false, 0, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, "interrupted");
        }
    }

    /**
     * Substitutes the placeholders. {@code null} when the result would not be a
     * usable URL — an unsupported scheme, or a value that failed the grammar.
     */
    private @Nullable String resolveUrl(@Nullable String tenantId) {
        String template = properties.getPlacement().getWebhookUrl();
        String resolved = template;
        if (template.contains("{tenant}")) {
            if (tenantId == null || !SAFE_PLACEHOLDER.matcher(tenantId).matches()) {
                log.warn("Placement webhook: refusing to substitute tenant '{}' into the URL",
                        tenantId);
                return null;
            }
            resolved = resolved.replace("{tenant}", tenantId);
        }
        if (resolved.contains("{cluster}")) {
            String clusterId = properties.getId();
            if (clusterId == null || !SAFE_PLACEHOLDER.matcher(clusterId).matches()) {
                log.warn("Placement webhook: refusing to substitute cluster '{}' into the URL",
                        clusterId);
                return null;
            }
            resolved = resolved.replace("{cluster}", clusterId);
        }
        if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) {
            log.warn("Placement webhook: '{}' is not an http(s) URL — not sending", resolved);
            return null;
        }
        return resolved;
    }

    /** Whether the configured URL asks for a per-tenant delivery. */
    public boolean isPerTenant() {
        return isConfigured() && properties.getPlacement().getWebhookUrl().contains("{tenant}");
    }

    private static String trimForLog(@Nullable String body) {
        if (body == null || body.isBlank()) return "";
        String single = body.replaceAll("\\s+", " ").trim();
        return single.length() <= 300 ? single : single.substring(0, 300) + "…";
    }

    /**
     * Outcome of one delivery. {@code detail} is the response body (trimmed) or
     * the failure reason — it goes into the log, and is where an {@code eta} or
     * a refusal reason from the provisioner becomes visible.
     */
    public record Result(boolean delivered, int status, String detail) {}
}
