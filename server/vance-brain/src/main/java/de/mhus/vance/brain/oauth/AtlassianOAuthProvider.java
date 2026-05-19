package de.mhus.vance.brain.oauth;

import de.mhus.vance.toolpack.core.PackHttpClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Atlassian OAuth 2.0 (3LO) — same RFC 6749 shape as the generic
 * flow, but Atlassian's token response doesn't carry the
 * {@code cloud_id} that tools need to address a specific
 * Jira/Confluence site. Right after the token exchange this provider
 * calls {@code /oauth/token/accessible-resources} to enumerate the
 * sites the freshly-granted token is scoped to, and stashes the
 * primary site's id (and name+url) in {@code extraClaims}.
 *
 * <p>Tenants with multi-site grants get all sites JSON-encoded under
 * {@code accessible_resources} — tool configs can pick the one they
 * need via {@code oauth.<provider>.extra}.
 *
 * <p>Refresh doesn't re-fetch accessible-resources — the set is stable
 * for the lifetime of the grant; the original {@code cloud_id} stays
 * valid. If the user revokes a site through Atlassian's UI, the next
 * tool call against that site fails with a 404 (or 403) anyway.
 */
@Component
@Slf4j
public class AtlassianOAuthProvider extends GenericOAuth2Provider {

    /** Bean-type identifier. */
    public static final String TYPE_ID = "atlassian";

    /** Atlassian-cloud-wide endpoint; same for every tenant. */
    public static final String ACCESSIBLE_RESOURCES_URL =
            "https://api.atlassian.com/oauth/token/accessible-resources";

    public AtlassianOAuthProvider() {
        super();
    }

    AtlassianOAuthProvider(PackHttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public OAuthTokenSet exchangeCode(
            OAuthProviderConfig cfg, String code, OAuthInitContext ctx) {
        OAuthTokenSet base = super.exchangeCode(cfg, code, ctx);
        return withAccessibleResources(cfg, base);
    }

    /**
     * Append accessible-resources metadata to a freshly-exchanged
     * token set. Failures here downgrade to a {@code WARN} log and the
     * unmodified token set — tenants with mis-scoped tokens still get
     * a working access token, the cloud_id is just absent and the
     * tools that need it will surface their own clearer error.
     *
     * <p>{@code extra.accessibleResourcesUrl} may override the default
     * endpoint (useful for staging environments).
     */
    private OAuthTokenSet withAccessibleResources(
            OAuthProviderConfig cfg, OAuthTokenSet tokens) {
        String url = stringOrDefault(cfg.extra().get("accessibleResourcesUrl"),
                ACCESSIBLE_RESOURCES_URL);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.client(PackHttpClient.TlsConfig.DEFAULT)
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("AtlassianOAuthProvider: accessible-resources interrupted — "
                    + "cloud_id will be absent");
            return tokens;
        } catch (java.io.IOException ex) {
            log.warn("AtlassianOAuthProvider: accessible-resources fetch failed: {} — "
                    + "cloud_id will be absent", ex.toString());
            return tokens;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("AtlassianOAuthProvider: accessible-resources returned HTTP {} — "
                    + "cloud_id will be absent", response.statusCode());
            return tokens;
        }
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (RuntimeException ex) {
            log.warn("AtlassianOAuthProvider: accessible-resources body unparseable: {} — "
                    + "cloud_id will be absent", ex.toString());
            return tokens;
        }
        if (!root.isArray() || root.isEmpty()) {
            log.warn("AtlassianOAuthProvider: accessible-resources returned no sites — "
                    + "the token grant covers no Atlassian site");
            return tokens;
        }
        Map<String, String> extra = new LinkedHashMap<>(tokens.extraClaims());
        JsonNode primary = root.get(0);
        String cloudId = stringOrNull(primary, "id");
        if (cloudId != null) extra.put("cloud_id", cloudId);
        String siteName = stringOrNull(primary, "name");
        if (siteName != null) extra.put("site_name", siteName);
        String siteUrl = stringOrNull(primary, "url");
        if (siteUrl != null) extra.put("site_url", siteUrl);
        // Verbatim resources list for multi-site tenants — tools can
        // pick the site they need via the JSON blob.
        extra.put("accessible_resources", root.toString());
        return new OAuthTokenSet(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.expiresAt(),
                extra);
    }

    private static @org.jspecify.annotations.Nullable String stringOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asString();
        return s.isEmpty() ? null : s;
    }

    private static String stringOrDefault(Object value, String fallback) {
        if (!(value instanceof String s) || s.isBlank()) return fallback;
        return s;
    }
}
