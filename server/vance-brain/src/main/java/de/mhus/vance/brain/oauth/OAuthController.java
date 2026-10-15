package de.mhus.vance.brain.oauth;

import de.mhus.vance.api.oauth.OAuthProviderListEntry;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * User-facing OAuth endpoints — list of configured providers, init
 * (redirect to provider), callback (consume code, persist tokens).
 *
 * <p>The endpoints are tenant-scoped under {@code /brain/{tenant}/oauth-client};
 * the tenant path variable is validated by the brain access filter
 * against the caller's JWT before requests reach this controller.
 * The callback explicitly re-validates state against the authenticated
 * user as a defense-in-depth CSRF / confused-deputy guard.
 *
 * <p>Tokens are persisted by {@link OAuthTokenStore} on the owner the flow
 * was started for — the calling user here; a shared project or tenant
 * login for an MCP server ({@code McpOAuthController}) uses the same
 * callback. Providers of type {@code mcp} come back on the fixed
 * {@code /_mcp/callback} path; the provider is then taken from the state.
 */
@RestController
@RequestMapping("/brain/{tenant}/oauth-client")
@Slf4j
public class OAuthController {

    private final OAuthConfigRegistry configRegistry;
    private final OAuthStateService stateService;
    private final OAuthFlowService flowService;
    private final OAuthTokenStore tokenStore;
    private final RequestAuthority authority;
    private final ApplicationEventPublisher events;

    public OAuthController(
            OAuthConfigRegistry configRegistry,
            OAuthStateService stateService,
            OAuthFlowService flowService,
            OAuthTokenStore tokenStore,
            RequestAuthority authority,
            ApplicationEventPublisher events) {
        this.configRegistry = configRegistry;
        this.stateService = stateService;
        this.flowService = flowService;
        this.tokenStore = tokenStore;
        this.authority = authority;
        this.events = events;
    }

    // ──────────────────── Providers list ────────────────────

    /**
     * Lists every provider the tenant has configured, with a
     * {@code connected} flag for the calling user. Drives the
     * Web-UI's "Connected Accounts" page.
     */
    @GetMapping("/providers")
    public List<OAuthProviderListEntry> listProviders(
            @PathVariable("tenant") String tenant, HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);
        String userId = authority.contextOf(request).subjectId();

        List<OAuthProviderListEntry> out = new ArrayList<>();
        for (ResolvedOAuthProvider rp : configRegistry.list(tenant)) {
            out.add(OAuthProviderListEntry.builder()
                    .providerId(rp.providerId())
                    .typeId(rp.typeId())
                    .title(McpOAuthProvider.title(rp.config()))
                    .connected(tokenStore.isConnected(tenant, OAuthTokenOwner.user(userId), rp.providerId()))
                    .build());
        }
        out.sort(Comparator.comparing(OAuthProviderListEntry::getProviderId));
        return out;
    }

    // ──────────────────── Init: redirect to provider ────────────────────

    /**
     * Starts the Authorization-Code flow. Mints a state token bound to
     * the calling tenant+user, asks the provider for the authorize URL,
     * and 302-redirects the browser to it. The browser comes back to
     * the callback endpoint with {@code code}+{@code state}.
     */
    @GetMapping("/{providerId}/init")
    public ResponseEntity<Void> init(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            @RequestParam(value = "returnTo", required = false) String returnTo,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);
        String userId = authority.contextOf(request).subjectId();

        ResolvedOAuthProvider provider = configRegistry
                .resolve(tenant, providerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No OAuth provider '" + providerId + "' for tenant '" + tenant + "'"));
        return redirect(startFlow(tenant, userId, provider, OAuthTokenOwner.user(userId), returnTo));
    }

    /** Starts a flow and maps provider failures to HTTP errors. */
    private URI startFlow(
            String tenant,
            String userId,
            ResolvedOAuthProvider provider,
            OAuthTokenOwner owner,
            @Nullable String returnTo) {
        String providerId = provider.providerId();
        if (GenericOAuth2Provider.missesClientSecret(provider.config())) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED,
                    "OAuth provider '" + providerId
                            + "' has no client secret — set tenant setting '"
                            + OAuthProviderLoader.clientSecretKey(providerId) + "'");
        }
        try {
            return flowService.start(tenant, userId, provider, owner, returnTo);
        } catch (RuntimeException ex) {
            log.warn("OAuth init: provider '{}' failed to build authorize URI: {}", providerId, ex.toString());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "OAuth provider '" + providerId + "' failed to start the flow",
                    ex);
        }
    }

    // ──────────────────── Callback: consume code, persist tokens ─────────

    /**
     * Provider-redirected entrypoint. Validates the state, exchanges
     * the {@code code} for tokens, persists them for the flow's owner,
     * and 302-redirects the browser to the original {@code returnTo}
     * (or to {@code /} if absent).
     */
    @GetMapping("/{providerId}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);
        String userId = authority.contextOf(request).subjectId();

        Optional<OAuthStateService.Consumed> consumed = stateService.consume(state, tenant, userId);
        if (consumed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OAuth state");
        }
        boolean mcpCallback = OAuthFlowService.MCP_CALLBACK_SEGMENT.equals(providerId);
        String effectiveProviderId = mcpCallback ? consumed.get().providerId() : providerId;
        // The state binds to a specific providerId; reject when the
        // URL path tries to switch provider mid-flow (mix-up attack).
        if (!effectiveProviderId.equals(consumed.get().providerId())) {
            log.warn(
                    "OAuth callback: providerId mismatch — url='{}' state='{}'",
                    providerId,
                    consumed.get().providerId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state does not match providerId in URL");
        }

        ResolvedOAuthProvider provider = configRegistry
                .resolve(tenant, effectiveProviderId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No OAuth provider '" + effectiveProviderId + "' for tenant '" + tenant + "'"));
        // Each callback path serves exactly one kind of provider: the shared
        // MCP path only mcp providers, the per-provider path everything else.
        if (mcpCallback != McpOAuthProvider.TYPE_ID.equals(provider.typeId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "OAuth callback path does not match provider '" + effectiveProviderId + "'");
        }

        String redirectUri = flowService.redirectUriFor(tenant, provider.config());
        OAuthInitContext ctx = new OAuthInitContext(
                tenant,
                userId,
                state,
                redirectUri,
                consumed.get().returnTo(),
                consumed.get().codeVerifier());

        OAuthTokenSet tokens;
        try {
            tokens = provider.provider().exchangeCode(provider.config(), code, ctx);
        } catch (OAuthFlowException ex) {
            log.warn("OAuth callback: provider '{}' rejected code exchange: {}", effectiveProviderId, ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OAuth provider '" + effectiveProviderId + "' rejected token exchange: " + ex.getMessage(),
                    ex);
        } catch (RuntimeException ex) {
            log.warn("OAuth callback: provider '{}' threw during exchange: {}", effectiveProviderId, ex.toString());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "OAuth provider '" + effectiveProviderId + "' failed during token exchange",
                    ex);
        }

        OAuthTokenOwner owner = consumed.get().owner() == null
                ? OAuthTokenOwner.user(userId)
                : consumed.get().owner();
        tokenStore.persist(tenant, owner, effectiveProviderId, tokens, /*connect*/ true);
        if (owner.shared()) {
            // A new shared login replaces the account every pooled
            // connection of this provider authenticated with.
            events.publishEvent(new OAuthDisconnectedEvent(tenant, null, effectiveProviderId));
        }
        log.info(
                "OAuth connected: tenant='{}' user='{}' owner='{}' provider='{}'",
                tenant,
                userId,
                owner.storageProjectId(),
                effectiveProviderId);

        URI returnUri = resolveReturnUri(consumed.get().returnTo());
        return ResponseEntity.status(HttpStatus.FOUND).location(returnUri).build();
    }

    // ──────────────────── Disconnect ────────────────────

    /**
     * Removes every per-user setting for {@code (tenant, user, providerId)}.
     * Tokens at the provider end stay until revoked there; this endpoint
     * only erases the local copy so the next tool call fails fast and
     * the Web-UI shows the provider as disconnected.
     */
    @DeleteMapping("/{providerId}/connection")
    public ResponseEntity<Void> disconnect(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);
        String userId = authority.contextOf(request).subjectId();
        long removed = tokenStore.wipe(tenant, OAuthTokenOwner.user(userId), providerId);

        // Notify cached connection pools (MCP, REST) so any open
        // session bound to the freshly-wiped tokens drops immediately.
        // A new tool call afterwards rebuilds the connection from a
        // clean slate and surfaces a proper "user must reconnect" path.
        events.publishEvent(new OAuthDisconnectedEvent(tenant, userId, providerId));

        log.info(
                "OAuth disconnected: tenant='{}' user='{}' provider='{}' settings_removed={}",
                tenant,
                userId,
                providerId,
                removed);
        return ResponseEntity.noContent().build();
    }

    // ──────────────────── Internals ────────────────────

    static ResponseEntity<Void> redirect(URI target) {
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }

    private static URI resolveReturnUri(@Nullable String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return URI.create("/");
        }
        // Only relative paths are accepted — never let a caller send the
        // browser to a foreign origin. A leading slash forces same-origin
        // navigation in the browser.
        if (returnTo.startsWith("/") && !returnTo.startsWith("//")) {
            return URI.create(returnTo);
        }
        return URI.create("/");
    }
}
