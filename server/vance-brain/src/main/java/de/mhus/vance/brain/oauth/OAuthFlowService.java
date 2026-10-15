package de.mhus.vance.brain.oauth;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Starts an Authorization-Code flow against a configured provider: mints
 * the PKCE verifier and the state, and asks the provider bean for the
 * authorize URL. Shared by the Connected-Accounts init endpoint and the
 * MCP-server login, which differ only in whose tokens the flow produces
 * ({@link OAuthTokenOwner}).
 *
 * <p>Redirect URI: {@code …/oauth-client/<providerId>/callback}; providers of
 * type {@code mcp} use the one fixed {@code …/oauth-client/_mcp/callback}
 * instead, because a Client ID Metadata Document has to list its redirect
 * URIs exactly and must not change with every new MCP server.
 */
@Service
@Slf4j
public class OAuthFlowService {

    /** Path segment of the shared callback of all {@code mcp} providers. */
    public static final String MCP_CALLBACK_SEGMENT = "_mcp";

    /** PKCE verifier length — 32 bytes Base64URL ≈ 43 chars (RFC 7636 §4.1 minimum). */
    private static final int PKCE_VERIFIER_BYTES = 32;

    private final OAuthStateService stateService;
    private final String publicBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthFlowService(
            OAuthStateService stateService, @Value("${vance.web.public-base-url}") String publicBaseUrl) {
        this.stateService = stateService;
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    }

    /**
     * Authorize URL the browser is sent to.
     *
     * @throws OAuthFlowException when the provider cannot build it
     */
    public URI start(
            String tenant,
            String userId,
            ResolvedOAuthProvider provider,
            OAuthTokenOwner owner,
            @Nullable String returnTo) {
        String providerId = provider.providerId();
        String codeVerifier = GenericOAuth2Provider.isPkceEnabled(provider.config()) ? mintPkceVerifier() : null;
        String state = owner.kind() == OAuthTokenOwner.Kind.USER
                ? stateService.start(tenant, userId, providerId, returnTo, codeVerifier)
                : stateService.start(tenant, userId, providerId, owner, returnTo, codeVerifier);
        OAuthInitContext ctx = new OAuthInitContext(
                tenant, userId, state, redirectUriFor(tenant, provider.config()), returnTo, codeVerifier);
        return provider.provider().buildAuthorizeUri(provider.config(), ctx);
    }

    /** Absolute callback URL the provider redirects back to. */
    public String redirectUriFor(String tenant, OAuthProviderConfig cfg) {
        String segment = McpOAuthProvider.TYPE_ID.equals(cfg.typeId()) ? MCP_CALLBACK_SEGMENT : cfg.providerId();
        return UriComponentsBuilder.fromUriString(publicBaseUrl)
                .pathSegment("brain", tenant, "oauth-client", segment, "callback")
                .build()
                .toUriString();
    }

    /** The deployment's public base URL, without trailing slash. */
    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * Mint a PKCE code-verifier (RFC 7636 §4.1) — 32 bytes of
     * {@link SecureRandom} entropy, Base64URL-encoded without padding.
     */
    private String mintPkceVerifier() {
        byte[] bytes = new byte[PKCE_VERIFIER_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripTrailingSlash(@Nullable String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
