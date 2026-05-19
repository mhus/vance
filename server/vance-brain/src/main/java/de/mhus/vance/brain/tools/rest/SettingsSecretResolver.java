package de.mhus.vance.brain.tools.rest;

import de.mhus.vance.brain.oauth.OAuthExpiredException;
import de.mhus.vance.brain.oauth.OAuthTokenRefresher;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Server-side {@link SecretResolver}. Substitutes {@code {{secret:…}}}
 * references in tool inputs (URLs, headers, bodies) with the matching
 * setting value from {@link SettingService}.
 *
 * <h2>Syntax</h2>
 * <pre>
 *   {{secret:&lt;key&gt;}}                      cascade lookup (default)
 *   {{secret:project:&lt;key&gt;}}              explicit project scope
 *   {{secret:tenant:&lt;key&gt;}}               explicit tenant scope ({@code _tenant} project)
 *   {{secret:user:&lt;key&gt;}}                 explicit user scope
 *   {{secret:user:oauth.&lt;providerId&gt;.access_token}}   OAuth access token (auto-refresh)
 * </pre>
 *
 * <p>The default form keeps the historical cascade
 * {@code think-process → projectId → _tenant}. The explicit prefixes
 * route to single-layer reads. The {@code user:} scope additionally
 * detects the OAuth access-token convention
 * ({@code oauth.<providerId>.access_token}) and goes through
 * {@link OAuthTokenRefresher}, which transparently refreshes the
 * token when it's about to expire.
 *
 * <p>Unresolved references substitute to the empty string with a
 * {@code WARN} log line — REST calls that depend on the auth header
 * will then fail with a 401, the right escalation path for the LLM.
 * The exception is {@link OAuthExpiredException}: that propagates
 * unchanged so the Web-UI can render a "Reconnect Provider" banner
 * instead of letting the tool fail with an opaque blank header.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsSecretResolver implements SecretResolver {

    /** Matches {@code {{secret:any.dotted.key}}}. Group 1 = scope-prefixed key body. */
    private static final Pattern REF = Pattern.compile(
            "\\{\\{\\s*secret\\s*:\\s*([^}\\s]+)\\s*\\}\\}");

    /** Recognised scope prefixes (mutually exclusive). */
    private static final String SCOPE_USER = "user";
    private static final String SCOPE_TENANT = "tenant";
    private static final String SCOPE_PROJECT = "project";

    /** Pattern that identifies a user-scope OAuth access-token key. */
    private static final Pattern OAUTH_ACCESS_TOKEN_KEY =
            Pattern.compile("^oauth\\.([^.]+)\\.access_token$");

    private final SettingService settings;
    private final OAuthTokenRefresher oauthTokenRefresher;

    @Override
    public @Nullable String resolve(@Nullable String input, ToolInvocationContext ctx) {
        if (input == null || input.isEmpty()) return input;
        Matcher m = REF.matcher(input);
        if (!m.find()) return input;
        StringBuilder out = new StringBuilder();
        m.reset();
        int last = 0;
        while (m.find()) {
            out.append(input, last, m.start());
            String body = m.group(1);
            String resolved = resolveOne(body, ctx);
            if (resolved == null) {
                log.warn("SettingsSecretResolver: no value found for '{}' "
                                + "(tenant='{}', project='{}', process='{}', user='{}') "
                                + "— substituting empty string",
                        body,
                        ctx == null ? null : ctx.tenantId(),
                        ctx == null ? null : ctx.projectId(),
                        ctx == null ? null : ctx.processId(),
                        ctx == null ? null : ctx.userId());
                resolved = "";
            }
            out.append(resolved);
            last = m.end();
        }
        out.append(input, last, input.length());
        return out.toString();
    }

    private @Nullable String resolveOne(String body, @Nullable ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            return null;
        }
        Scoped scoped = parseScope(body);
        return switch (scoped.scope()) {
            case SCOPE_USER -> resolveUser(scoped.key(), ctx);
            case SCOPE_TENANT -> resolveTenant(scoped.key(), ctx);
            case SCOPE_PROJECT -> resolveProject(scoped.key(), ctx);
            default -> resolveCascade(scoped.key(), ctx);
        };
    }

    private @Nullable String resolveUser(String key, ToolInvocationContext ctx) {
        if (ctx.userId() == null || ctx.userId().isBlank()) {
            log.warn("SettingsSecretResolver: user-scope lookup '{}' requested without a userId "
                            + "in ToolInvocationContext (tenant='{}', project='{}')",
                    key, ctx.tenantId(), ctx.projectId());
            return null;
        }
        Matcher oauth = OAUTH_ACCESS_TOKEN_KEY.matcher(key);
        if (oauth.matches()) {
            String providerId = oauth.group(1);
            // OAuthExpiredException intentionally NOT caught here — it
            // carries the "user must reconnect" signal up to the tool
            // dispatch / web-UI layer.
            return oauthTokenRefresher.resolveAccessToken(
                    ctx.tenantId(), ctx.userId(), providerId);
        }
        return settings.getDecryptedUserPassword(ctx.tenantId(), ctx.userId(), key);
    }

    private @Nullable String resolveTenant(String key, ToolInvocationContext ctx) {
        return settings.getDecryptedPassword(ctx.tenantId(),
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    private @Nullable String resolveProject(String key, ToolInvocationContext ctx) {
        if (ctx.projectId() == null || ctx.projectId().isBlank()) {
            return null;
        }
        return settings.getDecryptedPassword(ctx.tenantId(),
                SettingService.SCOPE_PROJECT, ctx.projectId(), key);
    }

    private @Nullable String resolveCascade(String key, ToolInvocationContext ctx) {
        return settings.getDecryptedPasswordCascade(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), key);
    }

    /** Split {@code "user:oauth.slack.access_token"} → ({@code user}, {@code oauth.slack.access_token}). */
    private static Scoped parseScope(String body) {
        int colon = body.indexOf(':');
        if (colon < 0) {
            return new Scoped("", body);
        }
        String prefix = body.substring(0, colon);
        if (!isScopePrefix(prefix)) {
            // Not a known scope prefix — treat the whole thing as a key.
            // This keeps backward compatibility with keys that happen to
            // contain a colon (currently none, but defensive).
            return new Scoped("", body);
        }
        return new Scoped(prefix, body.substring(colon + 1));
    }

    private static boolean isScopePrefix(String s) {
        return SCOPE_USER.equals(s) || SCOPE_TENANT.equals(s) || SCOPE_PROJECT.equals(s);
    }

    private record Scoped(String scope, String key) {
    }
}
