package de.mhus.vance.brain.tools.mcp.oauth;

import de.mhus.vance.brain.oauth.GenericOAuth2Provider;
import de.mhus.vance.brain.oauth.McpOAuthProvider;
import de.mhus.vance.brain.oauth.OAuthConfigRegistry;
import de.mhus.vance.brain.oauth.OAuthDisconnectedEvent;
import de.mhus.vance.brain.oauth.OAuthFlowService;
import de.mhus.vance.brain.oauth.OAuthProviderConfig;
import de.mhus.vance.brain.oauth.OAuthProviderLoader;
import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.brain.oauth.OAuthTokenStore;
import de.mhus.vance.brain.oauth.ResolvedOAuthProvider;
import de.mhus.vance.brain.oauthserver.ClientMetadataDocumentResolver;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.mcp.McpToolPackFactory;
import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.PackHttpClient;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Login management for MCP server tools with {@code auth.type: oauth}.
 *
 * <p>The provider document ({@code _vance/oauth/<providerId>.yaml}, type
 * {@code mcp}) is created on the first login, not when the tool is saved —
 * saving stays a plain document write without network. Creating it means:
 * probe the server, read its protected-resource and authorization-server
 * metadata, and obtain a client id — a Client ID Metadata Document served
 * by this brain when the authorization server supports it and the brain is
 * publicly reachable, else dynamic client registration, else the
 * {@code clientId} configured on the tool. After that the generic OAuth
 * client stack takes over (init, callback, refresh).
 *
 * <p>An existing managed provider is reused only while it still matches the
 * tool: same server URL, same hand-registered client (id and secret), same
 * explicit scopes, same TLS settings, and recorded issuer and resource.
 * Anything else re-runs the discovery and rewrites the document.
 *
 * <p>Managed provider documents are kept when a tool is deleted — another
 * project may use the same server, and a stale document is harmless.
 */
@Service
@Slf4j
public class McpOAuthService {

    /** Status values of {@link #status}. */
    public static final String STATUS_CONNECTED = "CONNECTED";

    public static final String STATUS_NOT_CONNECTED = "NOT_CONNECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** {@code extra.registration} of a client registered by hand ({@code auth.clientId}). */
    static final String REGISTRATION_MANUAL = "manual";

    private final OAuthConfigRegistry configRegistry;
    private final OAuthFlowService flowService;
    private final OAuthTokenStore tokenStore;
    private final McpAuthDiscoveryClient discovery;
    private final DocumentService documentService;
    private final SettingService settingService;
    private final SettingsSecretResolver secretResolver;
    private final ApplicationEventPublisher events;
    private final ServerToolService serverToolService;
    private final Clock clock;

    /** Per-tenant/provider monitors so two first logins don't register twice on this pod. */
    private final Object[] locks = newStripes(32);

    public McpOAuthService(
            OAuthConfigRegistry configRegistry,
            OAuthFlowService flowService,
            OAuthTokenStore tokenStore,
            McpAuthDiscoveryClient discovery,
            DocumentService documentService,
            SettingService settingService,
            SettingsSecretResolver secretResolver,
            ApplicationEventPublisher events,
            ServerToolService serverToolService) {
        this.configRegistry = configRegistry;
        this.flowService = flowService;
        this.tokenStore = tokenStore;
        this.discovery = discovery;
        this.documentService = documentService;
        this.settingService = settingService;
        this.secretResolver = secretResolver;
        this.events = events;
        this.serverToolService = serverToolService;
        this.clock = Clock.systemUTC();
    }

    // ──────────────────── Tool ────────────────────

    /** An {@code mcp_server} tool with {@code auth.type: oauth}, as resolved in a project. */
    public record McpServerTool(ServerToolConfig config, McpOAuthSettings settings) {

        /** Display name: the description, else the tool name. */
        public String title() {
            return config.description() == null || config.description().isBlank()
                    ? config.name()
                    : config.description();
        }
    }

    /**
     * The OAuth MCP server tool {@code name} as the cascade resolves it in
     * {@code project}.
     *
     * @throws McpOAuthException when there is no such tool or it does not use OAuth
     */
    public McpServerTool resolveTool(String tenant, String project, String name) {
        ServerToolConfig config = serverToolService
                .findConfig(tenant, project, name)
                .orElseThrow(() -> new McpOAuthException("no server tool '" + name + "' in project '" + project + "'"));
        if (!McpToolPackFactory.TYPE_ID.equals(config.type())) {
            throw new McpOAuthException("server tool '" + name + "' is not an MCP server");
        }
        McpOAuthSettings settings;
        try {
            settings = McpOAuthSettings.of(config.parameters())
                    .orElseThrow(
                            () -> new McpOAuthException("server tool '" + name + "' does not use auth.type 'oauth'"));
        } catch (IllegalArgumentException e) {
            throw new McpOAuthException(e.getMessage(), e);
        }
        return new McpServerTool(config, settings);
    }

    /**
     * The permission a caller needs to log in or out for the login scope:
     * READ on the project for a personal login, ADMIN on the project or the
     * tenant for a shared account.
     */
    public static Resource loginResource(McpOAuthSettings settings, String tenant, String project) {
        return settings.loginScope() == McpOAuthSettings.LoginScope.TENANT
                ? new Resource.Tenant(tenant)
                : new Resource.Project(tenant, project);
    }

    /** See {@link #loginResource}. */
    public static Action loginAction(McpOAuthSettings settings) {
        return settings.loginScope() == McpOAuthSettings.LoginScope.USER ? Action.READ : Action.ADMIN;
    }

    // ──────────────────── Provider ────────────────────

    /**
     * The {@code mcp} provider for the server of {@code server}; created on
     * first use, re-created when it no longer matches the tool
     * ({@link #matches}).
     *
     * @param project the project the login is started in — a tool defined
     *                there resolves its {@code auth.clientSecret} reference in
     *                that project; a tenant-wide or bundled tool in {@code _tenant}
     * @throws McpOAuthException when discovery or registration fails
     */
    public ResolvedOAuthProvider ensureProvider(String tenant, String project, McpServerTool server, WriteActor actor) {
        McpOAuthSettings settings = server.settings();
        String providerId = settings.providerId();
        PackHttpClient.TlsConfig tls = tlsOf(server.config().parameters());
        String clientSecret = resolveClientSecret(tenant, project, server);
        Optional<ResolvedOAuthProvider> existing = configRegistry.resolve(tenant, providerId);
        if (existing.isPresent() && matches(existing.get(), settings, tls, clientSecret)) {
            return existing.get();
        }
        synchronized (lockFor(tenant, providerId)) {
            existing = configRegistry.resolve(tenant, providerId);
            if (existing.isPresent() && matches(existing.get(), settings, tls, clientSecret)) {
                return existing.get();
            }
            ProviderDraft draft = discover(tenant, settings, tls, clientSecret, /*register*/ true);
            writeProvider(tenant, providerId, settings, tls, draft, server.title(), actor);
            return configRegistry
                    .resolve(tenant, providerId)
                    .orElseThrow(() -> new McpOAuthException(
                            "OAuth provider '" + providerId + "' could not be loaded after it was written"));
        }
    }

    /**
     * Whether an existing provider can serve the tool as configured now. A
     * provider the brain did not write ({@code extra.managed} absent) is the
     * operator's and is always used as it is.
     */
    static boolean matches(
            ResolvedOAuthProvider existing,
            McpOAuthSettings settings,
            PackHttpClient.TlsConfig tls,
            @Nullable String clientSecret) {
        OAuthProviderConfig cfg = existing.config();
        Map<String, Object> extra = cfg.extra();
        if (!McpOAuthProvider.TYPE_ID.equals(cfg.typeId())
                || !Boolean.TRUE.equals(extra.get(McpOAuthProvider.EXTRA_MANAGED))) {
            return true;
        }
        if (!settings.url().equals(extra.get(McpOAuthProvider.EXTRA_MCP_URL))
                || !(extra.get(GenericOAuth2Provider.EXTRA_ISSUER) instanceof String)
                || !(extra.get(McpOAuthProvider.EXTRA_RESOURCE) instanceof String)) {
            return false;
        }
        if (!tls.equals(storedTls(extra))) {
            return false;
        }
        boolean manual = REGISTRATION_MANUAL.equals(extra.get(McpOAuthProvider.EXTRA_REGISTRATION));
        if (settings.clientId() != null) {
            if (!manual || !settings.clientId().equals(cfg.clientId())) {
                return false;
            }
            if (!Objects.equals(blankToEmpty(clientSecret), blankToEmpty(cfg.clientSecret()))) {
                return false;
            }
        } else if (manual) {
            return false;
        }
        return settings.scopes().isEmpty() || settings.scopes().equals(cfg.scopes());
    }

    /**
     * What the discovery finds for the server — without registering or
     * writing anything. For the connection test in the admin UI.
     */
    public Discovery inspect(String tenant, McpOAuthSettings settings, Map<String, Object> toolParameters) {
        ProviderDraft draft = discover(tenant, settings, tlsOf(toolParameters), null, /*register*/ false);
        return new Discovery(draft.authServer().issuer(), draft.registration(), draft.scopes());
    }

    /** Discovery result for {@link #inspect}. */
    public record Discovery(String issuer, String registration, List<String> scopes) {}

    private record ProviderDraft(
            McpAuthDiscoveryClient.AuthServerMetadata authServer,
            String resource,
            String registration,
            @Nullable String clientId,
            @Nullable String clientSecret,
            List<String> scopes) {}

    private ProviderDraft discover(
            String tenant,
            McpOAuthSettings settings,
            PackHttpClient.TlsConfig tls,
            @Nullable String manualClientSecret,
            boolean register) {
        String url = settings.url();
        McpAuthDiscoveryClient.Challenge challenge = discovery.probe(url, tls);
        if (!challenge.authRequired()) {
            throw new McpOAuthException(
                    "the MCP server accepts requests without login — set auth.type to 'none' instead of 'oauth'");
        }
        McpAuthDiscoveryClient.ResourceMetadata resource =
                discovery.resourceMetadata(url, challenge.resourceMetadataUrl(), tls);
        McpAuthDiscoveryClient.AuthServerMetadata authServer = discovery.authServerMetadata(
                url, resource.authorizationServers().get(0), tls);
        // MCP authorization: a client MUST refuse to proceed when the server
        // does not announce S256 — an absent list is not an implicit yes.
        if (!authServer.codeChallengeMethods().contains("S256")) {
            throw new McpOAuthException(
                    authServer.codeChallengeMethods().isEmpty()
                            ? "the authorization server does not announce PKCE support (code_challenge_methods_supported)"
                            : "the authorization server does not support PKCE with S256");
        }
        List<String> scopes = !settings.scopes().isEmpty()
                ? settings.scopes()
                : !challenge.scopes().isEmpty() ? challenge.scopes() : resource.scopesSupported();
        String res = resource.resource();

        if (settings.clientId() != null) {
            return new ProviderDraft(
                    authServer, res, REGISTRATION_MANUAL, settings.clientId(), manualClientSecret, scopes);
        }
        if (authServer.clientIdMetadataDocumentSupported() && clientMetadataDocumentReachable()) {
            return new ProviderDraft(authServer, res, "cimd", clientMetadataDocumentUrl(tenant), null, scopes);
        }
        if (authServer.registrationEndpoint() != null) {
            if (!register) {
                return new ProviderDraft(authServer, res, "dcr", null, null, scopes);
            }
            McpAuthDiscoveryClient.ClientRegistration registration = discovery.register(
                    url,
                    authServer.registrationEndpoint(),
                    clientName(tenant),
                    List.of(mcpRedirectUri(tenant)),
                    scopes,
                    tls);
            return new ProviderDraft(
                    authServer, res, "dcr", registration.clientId(), registration.clientSecret(), scopes);
        }
        if (!register) {
            return new ProviderDraft(authServer, res, "none", null, null, scopes);
        }
        throw new McpOAuthException("the authorization server offers no client registration — "
                + "register a client there and set auth.clientId (and auth.clientSecret) on the tool");
    }

    /**
     * The tool's {@code auth.clientSecret}, resolved like a connector
     * credential in the project that defines the tool — {@code project} for a
     * project tool, {@code _tenant} for a tenant-wide or bundled one. No
     * user: the secret belongs to the shared client registration, not to
     * whoever happens to log in first.
     */
    private @Nullable String resolveClientSecret(String tenant, String project, McpServerTool server) {
        String reference = server.settings().clientSecret();
        if (reference == null) {
            return null;
        }
        String scope = server.config().source() == ServerToolConfig.Source.PROJECT
                ? project
                : HomeBootstrapService.TENANT_PROJECT_NAME;
        return secretResolver.substituteForConnector(
                reference, new ToolInvocationContext(tenant, scope, null, null, null));
    }

    private void writeProvider(
            String tenant,
            String providerId,
            McpOAuthSettings settings,
            PackHttpClient.TlsConfig tls,
            ProviderDraft draft,
            String title,
            WriteActor actor) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put(McpOAuthProvider.EXTRA_MCP_URL, settings.url());
        extra.put(McpOAuthProvider.EXTRA_RESOURCE, draft.resource());
        extra.put(GenericOAuth2Provider.EXTRA_ISSUER, draft.authServer().issuer());
        extra.put(
                GenericOAuth2Provider.EXTRA_ISS_PARAMETER_SUPPORTED,
                draft.authServer().issParameterSupported());
        extra.put(GenericOAuth2Provider.EXTRA_USE_PKCE, true);
        extra.put(McpOAuthProvider.EXTRA_REGISTRATION, draft.registration());
        extra.put(McpOAuthProvider.EXTRA_TITLE, title);
        extra.put(McpOAuthProvider.EXTRA_MANAGED, true);
        if (!PackHttpClient.TlsConfig.DEFAULT.equals(tls)) {
            Map<String, Object> tlsBlock = new LinkedHashMap<>();
            tlsBlock.put("skipVerification", tls.skipVerification());
            if (tls.trustedCaPem() != null) {
                tlsBlock.put("trustedCaPemPath", tls.trustedCaPem());
            }
            extra.put(McpOAuthProvider.EXTRA_TLS, tlsBlock);
        }

        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("type", McpOAuthProvider.TYPE_ID);
        yaml.put("clientId", draft.clientId());
        yaml.put("authorizeUrl", draft.authServer().authorizationEndpoint());
        yaml.put("tokenUrl", draft.authServer().tokenEndpoint());
        yaml.put("scopes", new ArrayList<>(draft.scopes()));
        yaml.put("extra", extra);
        String body = dumpYaml(yaml);

        String path = OAuthProviderLoader.pathFor(providerId);
        Optional<DocumentDocument> doc =
                documentService.findByPath(tenant, HomeBootstrapService.TENANT_PROJECT_NAME, path);
        if (doc.isPresent()) {
            documentService.update(doc.get().getId(), null, null, body, null, actor);
        } else {
            documentService.createText(
                    tenant,
                    HomeBootstrapService.TENANT_PROJECT_NAME,
                    path,
                    "MCP server login: " + title,
                    null,
                    body,
                    actor.subject().subjectId(),
                    actor);
        }
        String secretKey = OAuthProviderLoader.clientSecretKey(providerId);
        if (draft.clientSecret() != null && !draft.clientSecret().isBlank()) {
            settingService.setEncryptedPassword(
                    tenant,
                    SettingService.SCOPE_PROJECT,
                    HomeBootstrapService.TENANT_PROJECT_NAME,
                    secretKey,
                    draft.clientSecret());
        } else {
            settingService.delete(
                    tenant, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, secretKey);
        }
        configRegistry.refreshOne(tenant, providerId);
        log.info(
                "MCP OAuth provider created: tenant='{}' provider='{}' issuer='{}' registration={}",
                tenant,
                providerId,
                draft.authServer().issuer(),
                draft.registration());
    }

    // ──────────────────── Login state ────────────────────

    /** Login state of {@code owner} for the server of {@code settings}. */
    public String status(String tenant, McpOAuthSettings settings, OAuthTokenOwner owner) {
        String providerId = settings.providerId();
        if (!tokenStore.isConnected(tenant, owner, providerId)) {
            return STATUS_NOT_CONNECTED;
        }
        if (tokenStore.refreshToken(tenant, owner, providerId) != null) {
            return STATUS_CONNECTED;
        }
        String expires = tokenStore.expiresAt(tenant, owner, providerId);
        if (expires == null || expires.isBlank()) {
            return STATUS_CONNECTED;
        }
        try {
            return Instant.parse(expires).isAfter(clock.instant()) ? STATUS_CONNECTED : STATUS_EXPIRED;
        } catch (DateTimeParseException e) {
            return STATUS_CONNECTED;
        }
    }

    /** Removes the stored login of {@code owner} and drops connections that used it. */
    public void disconnect(String tenant, McpOAuthSettings settings, OAuthTokenOwner owner) {
        String providerId = settings.providerId();
        tokenStore.wipe(tenant, owner, providerId);
        String userId = owner.kind() == OAuthTokenOwner.Kind.USER
                ? owner.storageProjectId().substring(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX.length())
                : null;
        events.publishEvent(new OAuthDisconnectedEvent(tenant, userId, providerId));
    }

    // ──────────────────── Client ID Metadata Document ────────────────────

    /** URL under which this brain serves its client metadata for {@code tenant}. */
    public String clientMetadataDocumentUrl(String tenant) {
        return flowService.publicBaseUrl() + "/brain/" + tenant + "/oauth-client/client-metadata.json";
    }

    /** The Client ID Metadata Document of {@code tenant} — this brain as OAuth client of MCP servers. */
    public Map<String, Object> clientMetadataDocument(String tenant) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("client_id", clientMetadataDocumentUrl(tenant));
        doc.put("client_name", clientName(tenant));
        doc.put("client_uri", flowService.publicBaseUrl());
        doc.put("redirect_uris", List.of(mcpRedirectUri(tenant)));
        doc.put("grant_types", List.of("authorization_code", "refresh_token"));
        doc.put("response_types", List.of("code"));
        doc.put("token_endpoint_auth_method", "none");
        return doc;
    }

    /**
     * Whether an authorization server could fetch our metadata document:
     * the public base URL is HTTPS and resolves to public addresses.
     */
    boolean clientMetadataDocumentReachable() {
        URI base;
        try {
            base = URI.create(flowService.publicBaseUrl());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null) {
            return false;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(base.getHost())) {
                if (!ClientMetadataDocumentResolver.isPublic(address)) return false;
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private String mcpRedirectUri(String tenant) {
        return flowService.publicBaseUrl() + "/brain/" + tenant + "/oauth-client/"
                + OAuthFlowService.MCP_CALLBACK_SEGMENT + "/callback";
    }

    private static String clientName(String tenant) {
        return "Vancetope (" + tenant + ")";
    }

    // ──────────────────── Helpers ────────────────────

    @SuppressWarnings("unchecked")
    private static PackHttpClient.TlsConfig tlsOf(Map<String, Object> parameters) {
        Object tls = parameters.get("tls");
        return tls instanceof Map<?, ?> m
                ? PackHttpClient.TlsConfig.fromMap((Map<String, Object>) m)
                : PackHttpClient.TlsConfig.DEFAULT;
    }

    /** The TLS block the provider was written with ({@code extra.tls}). */
    @SuppressWarnings("unchecked")
    private static PackHttpClient.TlsConfig storedTls(Map<String, Object> extra) {
        return extra.get(McpOAuthProvider.EXTRA_TLS) instanceof Map<?, ?> m
                ? PackHttpClient.TlsConfig.fromMap((Map<String, Object>) m)
                : PackHttpClient.TlsConfig.DEFAULT;
    }

    private static String blankToEmpty(@Nullable String s) {
        return s == null || s.isBlank() ? "" : s;
    }

    private static String dumpYaml(Map<String, Object> yaml) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        return new Yaml(options).dump(yaml);
    }

    private Object lockFor(String tenant, String providerId) {
        return locks[Math.floorMod((tenant + "|" + providerId).hashCode(), locks.length)];
    }

    private static Object[] newStripes(int n) {
        Object[] a = new Object[n];
        for (int i = 0; i < n; i++) {
            a[i] = new Object();
        }
        return a;
    }
}
