package de.mhus.vance.brain.oauth;

import de.mhus.vance.toolpack.core.PackHttpClient;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Slack OAuth v2 — same Authorization-Code-Grant shape as RFC 6749,
 * but the token response nests the <i>user</i> credentials inside an
 * {@code authed_user} object:
 *
 * <pre>
 * {
 *   "ok": true,
 *   "access_token": "xoxb-…",       // bot token (top-level)
 *   "token_type": "bot",
 *   "scope": "channels:read,chat:write",
 *   "team": { "id": "T123", "name": "Acme" },
 *   "authed_user": {
 *     "id":           "U456",
 *     "access_token": "xoxp-…",     // user token (nested)  ← we want this
 *     "refresh_token": "xoxe.…",    // with Token Rotation
 *     "expires_in":   43200,
 *     "scope":        "im:read,im:write"
 *   }
 * }
 * </pre>
 *
 * <p>Vance acts on behalf of the connecting user, so the
 * {@code authed_user.access_token} is what tools sign their requests
 * with. The bot token and the {@code team} block are kept in
 * {@code extraClaims} so callers that need them (workspace-pinned
 * Slack tools) can pull them out via the {@code oauth.<provider>.extra}
 * user-setting.
 *
 * <p>Slack v2 only issues refresh tokens when the app has Token
 * Rotation enabled in the Slack dashboard. Without rotation the
 * tokens are non-expiring and the resolver short-circuits the refresh
 * path on its own ({@code expires_at} stays {@code null}).
 */
@Component
public class SlackOAuthProvider extends GenericOAuth2Provider {

    /** Bean-type identifier. */
    public static final String TYPE_ID = "slack";

    public SlackOAuthProvider() {
        super();
    }

    SlackOAuthProvider(PackHttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    /**
     * Slack v2 uses two parallel scope buckets in the authorize URL:
     * {@code scope=…} requests <em>bot</em>-scopes (installs the app and
     * mints {@code xoxb-…}), {@code user_scope=…} requests <em>user</em>-
     * scopes (mints {@code xoxp-…} for the consenting user).
     *
     * <p>Vance acts on the user's behalf, so the YAML's {@code scopes:}
     * list belongs in {@code user_scope}. Without this override the
     * generic implementation would send everything as bot-scopes — the
     * resulting token response would have an empty
     * {@code authed_user.scope} and every API call with the user-token
     * would fail with {@code missing_scope}.
     *
     * <p>If a deployment also needs a bot install (parallel
     * {@code xoxb-} token usable for unattended writes), it can add
     * {@code extra.botScopes: [chat:write, …]} to the provider yaml;
     * those are forwarded as the conventional {@code scope=} param and
     * surface in {@link #parseTokenResponse} as
     * {@code extra.bot_access_token}.
     */
    @Override
    protected void decorateAuthorizeParams(
            de.mhus.vance.brain.oauth.OAuthProviderConfig cfg,
            de.mhus.vance.brain.oauth.OAuthInitContext ctx,
            Map<String, String> params) {
        // The generic builder already wrote scopes into `scope=`. Slack
        // wants user-scopes in `user_scope=` — move them across.
        String scopes = params.remove("scope");
        if (scopes != null && !scopes.isBlank()) {
            // Slack accepts both space- and comma-separated; comma is
            // the API-doc convention for user_scope.
            params.put("user_scope", scopes.replace(' ', ','));
        }
        Object botScopesRaw = cfg.extra() == null ? null : cfg.extra().get("botScopes");
        if (botScopesRaw instanceof java.util.List<?> list && !list.isEmpty()) {
            java.util.List<String> bots = new java.util.ArrayList<>(list.size());
            for (Object s : list) {
                if (s != null) bots.add(s.toString());
            }
            if (!bots.isEmpty()) {
                params.put("scope", String.join(",", bots));
            }
        }
    }

    @Override
    protected OAuthTokenSet parseTokenResponse(JsonNode root, String providerId) {
        if (root.has("ok") && root.get("ok").isBoolean() && !root.get("ok").asBoolean()) {
            String err = stringOrNull(root, "error");
            throw new OAuthFlowException(providerId,
                    "Slack token response has ok=false"
                            + (err == null ? "" : " (error='" + err + "')"));
        }

        JsonNode authedUser = root.get("authed_user");
        if (authedUser == null || !authedUser.isObject()) {
            throw new OAuthFlowException(providerId,
                    "Slack token response missing 'authed_user' — "
                            + "Vance acts on behalf of the user, not the bot");
        }
        String accessToken = stringOrNull(authedUser, "access_token");
        if (accessToken == null) {
            throw new OAuthFlowException(providerId,
                    "Slack token response missing 'authed_user.access_token'");
        }
        String refreshToken = stringOrNull(authedUser, "refresh_token");
        Instant expiresAt = null;
        if (authedUser.has("expires_in") && authedUser.get("expires_in").isNumber()) {
            expiresAt = Instant.now().plusSeconds(authedUser.get("expires_in").asLong());
        }

        Map<String, String> extra = new LinkedHashMap<>();
        // user-side scopes go into the conventional 'scope' slot so the
        // refresher picks them up as oauth.<provider>.scopes.
        String userScope = stringOrNull(authedUser, "scope");
        if (userScope != null) {
            extra.put("scope", userScope);
        }
        // The Slack user id is a useful claim for tools that need to
        // call back to chat.postMessage etc.
        String userId = stringOrNull(authedUser, "id");
        if (userId != null) {
            extra.put("authed_user_id", userId);
        }
        // Workspace id from the top-level 'team' object — keep it as
        // the conventional team_id so tools can route to the right Slack.
        JsonNode team = root.get("team");
        if (team != null && team.isObject()) {
            String teamId = stringOrNull(team, "id");
            if (teamId != null) extra.put("team_id", teamId);
            String teamName = stringOrNull(team, "name");
            if (teamName != null) extra.put("team_name", teamName);
        }
        // Bot token + scope (top-level) — useful when a tool explicitly
        // wants the bot persona.
        String botToken = stringOrNull(root, "access_token");
        if (botToken != null && !botToken.equals(accessToken)) {
            extra.put("bot_access_token", botToken);
        }
        String botScope = stringOrNull(root, "scope");
        if (botScope != null) {
            extra.put("bot_scope", botScope);
        }
        // Everything else Slack chose to send — opaque pass-through.
        for (Iterator<String> it = root.propertyNames().iterator(); it.hasNext(); ) {
            String name = it.next();
            if ("ok".equals(name) || "access_token".equals(name)
                    || "scope".equals(name) || "authed_user".equals(name)
                    || "team".equals(name) || "token_type".equals(name)) {
                continue;
            }
            JsonNode v = root.get(name);
            if (v == null || v.isNull()) continue;
            extra.putIfAbsent(name, v.isValueNode() ? v.asString() : v.toString());
        }
        return new OAuthTokenSet(accessToken, refreshToken, expiresAt, extra);
    }

    private static @org.jspecify.annotations.Nullable String stringOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asString();
        return s.isEmpty() ? null : s;
    }
}
