package de.mhus.vance.brain.tools.rest;

import de.mhus.vance.brain.oauth.OAuthExpiredException;
import de.mhus.vance.brain.oauth.OAuthTokenRefresher;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SecretReferenceKeyPolicy;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.vault.VaultException;
import de.mhus.vance.shared.vault.VaultScope;
import de.mhus.vance.shared.vault.VaultService;
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
 *   {{secret:vault:&lt;key&gt;}}                external secret manager (Infisical, …)
 * </pre>
 *
 * <p>The {@code vault:} scope reads from the external secret manager bound at
 * the invocation's scope via {@link VaultService} (fixed prefix, provider-agnostic
 * — the backing manager is decided by the {@code vault.type} setting, not the
 * reference). A {@link VaultException} — no vault bound, provider unreachable,
 * auth failure — is caught and treated like any other unresolved reference
 * (empty + WARN), so a broken vault escalates to a downstream 401 rather than
 * propagating.
 *
 * <p>The default form keeps the historical cascade
 * {@code think-process → projectId → _tenant}. The explicit prefixes
 * route to single-layer reads. The {@code user:} scope additionally
 * detects the OAuth access-token convention
 * ({@code oauth.<providerId>.access_token}) and goes through
 * {@link OAuthTokenRefresher}, which transparently refreshes the
 * token when it's about to expire.
 *
 * <h2>This class is the reference-readability chokepoint</h2>
 * Two guards sit here, and they answer different questions.
 *
 * <p><b>Who may read this type?</b> {@link #resolve} — the restrictive default —
 * routes every setting-backed scope through
 * {@code SettingService.getReferenceSecret*}, which hands out
 * {@link de.mhus.vance.api.settings.SettingType#HIDDEN} values and refuses
 * {@code PASSWORD} ones. That is the whole enforcement of the split: the three
 * agent-writable surfaces — script {@code vance.secret(…)}, the Python
 * equivalent, and compose {@code secrets:} — all wrap their input into
 * {@code {{secret:…}}} and land here, so none of them needs its own guard.
 * {@link #resolveForConnector} reads both encrypted types instead, because an
 * operator-authored connector config is not a dynamic element. Compiled server
 * code reading a fixed key stays on {@code getDecryptedPassword} and is
 * unaffected. See {@code planning/setting-type-hidden.md} §5.
 *
 * <p><b>May this key be referenced at all?</b> {@link SecretReferenceKeyPolicy}
 * applies to <em>both</em> paths. Once connectors can resolve PASSWORD, the type
 * no longer keeps a reference away from {@code ai.provider.*.apiKey} — and a
 * connector document declares its target URL next to its headers. Reserved keys
 * are refused by name, before any scope is consulted. The {@code vault:} scope is
 * the one exception <em>at this layer</em>, because the key belongs to the
 * vault's namespace rather than ours; the settings-backed vault
 * ({@code SettingsVaultProvider}), whose keys <em>are</em> setting keys, runs the
 * same policy itself.
 *
 * <p>Unresolved references substitute to the empty string with a
 * {@code WARN} log line — REST calls that depend on the auth header
 * will then fail with a 401, the right escalation path for the LLM.
 * Two exceptions propagate unchanged instead, because both carry a
 * "a human has to change something" signal that the fail-closed-to-empty rule
 * would hide: {@link OAuthExpiredException} (Web-UI renders a "Reconnect
 * Provider" banner) and
 * {@link de.mhus.vance.shared.settings.SecretAccessDeniedException} (the
 * referenced setting is PASSWORD-typed and has to be re-typed as HIDDEN to be
 * usable here).
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
    private static final String SCOPE_VAULT = "vault";

    /** Pattern that identifies a user-scope OAuth access-token key. */
    private static final Pattern OAUTH_ACCESS_TOKEN_KEY =
            Pattern.compile("^oauth\\.([^.]+)\\.access_token$");

    private final SettingService settings;
    private final OAuthTokenRefresher oauthTokenRefresher;
    private final VaultService vaultService;
    private final SecretReferenceKeyPolicy referenceKeyPolicy;

    @Override
    public @Nullable String substitute(@Nullable String input, ToolInvocationContext ctx) {
        return substitute(input, ctx, /*connector*/ false);
    }

    @Override
    public @Nullable String substituteForConnector(
            @Nullable String input, ToolInvocationContext ctx) {
        return substitute(input, ctx, /*connector*/ true);
    }

    private @Nullable String substitute(
            @Nullable String input, ToolInvocationContext ctx, boolean connector) {
        if (input == null || input.isEmpty()) return input;
        Matcher m = REF.matcher(input);
        if (!m.find()) return input;
        StringBuilder out = new StringBuilder();
        m.reset();
        int last = 0;
        while (m.find()) {
            out.append(input, last, m.start());
            String body = m.group(1);
            String resolved = resolveOne(body, ctx, connector);
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

    private @Nullable String resolveOne(
            String body, @Nullable ToolInvocationContext ctx, boolean connector) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            return null;
        }
        Scoped scoped = parseScope(body);
        if (!SCOPE_VAULT.equals(scoped.scope())) {
            // Reserved server-internal keys are off-limits to every reference,
            // whichever scope names them and whoever asks. On the connector
            // path this is the *only* guard: PASSWORD is readable there by
            // design, so the type no longer keeps a tool document from
            // pointing an Authorization header at ai.provider.*.apiKey and
            // sending it to whatever URL that same document declares.
            //
            // Not applied to `vault:` *here* — that key names an entry in the
            // vault's own namespace, and an Infisical secret that happens to be
            // called `ai.provider.openai.apiKey` is not the setting of that
            // name; denying it would be a refusal based on a coincidence of
            // spelling. The exception is only honest as long as the namespace
            // really is foreign, which stopped being true for the default
            // installation when SettingsVaultProvider became the fallback — so
            // that provider applies the very same policy to the keys it reads,
            // where they *are* setting keys. The barrier holds on both paths;
            // it just sits at the place that knows whose namespace it is.
            referenceKeyPolicy.requireReferenceReadable(scoped.key());
        }
        return switch (scoped.scope()) {
            case SCOPE_USER -> resolveUser(scoped.key(), ctx, connector);
            case SCOPE_TENANT -> resolveTenant(scoped.key(), ctx, connector);
            case SCOPE_PROJECT -> resolveProject(scoped.key(), ctx, connector);
            case SCOPE_VAULT -> resolveVault(scoped.key(), ctx);
            default -> resolveCascade(scoped.key(), ctx, connector);
        };
    }

    private @Nullable String resolveUser(
            String key, ToolInvocationContext ctx, boolean connector) {
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
        String pw = connector
                ? settings.getDecryptedUserPassword(ctx.tenantId(), ctx.userId(), key)
                : settings.getReferenceUserSecret(ctx.tenantId(), ctx.userId(), key);
        if (pw != null) return pw;
        // Fall back to STRING-typed user settings — non-secret OAuth
        // metadata (cloud_id, site_url, …) is stored as STRING by the
        // OAuth callback's flat-extra projection, and tool templates need
        // to reach it through the same {{secret:user:…}} syntax that
        // already handles the access token.
        return settings.getUserStringValue(ctx.tenantId(), ctx.userId(), key);
    }

    private @Nullable String resolveTenant(
            String key, ToolInvocationContext ctx, boolean connector) {
        return connector
                ? settings.getDecryptedPassword(ctx.tenantId(), SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME, key)
                : settings.getReferenceSecret(ctx.tenantId(), SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    private @Nullable String resolveProject(
            String key, ToolInvocationContext ctx, boolean connector) {
        if (ctx.projectId() == null || ctx.projectId().isBlank()) {
            return null;
        }
        return connector
                ? settings.getDecryptedPassword(ctx.tenantId(),
                        SettingService.SCOPE_PROJECT, ctx.projectId(), key)
                : settings.getReferenceSecret(ctx.tenantId(),
                        SettingService.SCOPE_PROJECT, ctx.projectId(), key);
    }

    private @Nullable String resolveCascade(
            String key, ToolInvocationContext ctx, boolean connector) {
        return connector
                ? settings.getDecryptedPasswordCascade(
                        ctx.tenantId(), ctx.projectId(), ctx.processId(), key)
                : settings.getReferenceSecretCascade(
                        ctx.tenantId(), ctx.projectId(), ctx.processId(), key);
    }

    /**
     * {@code SecretAccessDeniedException} is intentionally not caught: with the
     * settings-backed vault as the default provider, a {@code vault:} reference
     * can land on a {@code PASSWORD}-typed setting, and that has to surface as
     * the named "re-type it to HIDDEN" failure just like the {@code project:} /
     * {@code tenant:} / {@code user:} scopes do. Only transport-ish
     * {@link VaultException}s fail closed to empty.
     */
    private @Nullable String resolveVault(String key, ToolInvocationContext ctx) {
        try {
            return vaultService.readSecret(
                    new VaultScope(ctx.tenantId(), ctx.userId(), ctx.projectId()), key);
        } catch (VaultException e) {
            // Fail closed like any other unresolved secret: returning null makes
            // the outer loop substitute empty (+ WARN) and the dependent call
            // escalates to a 401. The cause detail goes to trace so we don't
            // double-warn — the outer loop already logs the unresolved reference.
            log.trace("SettingsSecretResolver: vault lookup '{}' failed "
                            + "(tenant='{}', project='{}', user='{}'): {}",
                    key, ctx.tenantId(), ctx.projectId(), ctx.userId(), e.getMessage());
            return null;
        }
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
        return SCOPE_USER.equals(s) || SCOPE_TENANT.equals(s)
                || SCOPE_PROJECT.equals(s) || SCOPE_VAULT.equals(s);
    }

    private record Scoped(String scope, String key) {
    }
}
