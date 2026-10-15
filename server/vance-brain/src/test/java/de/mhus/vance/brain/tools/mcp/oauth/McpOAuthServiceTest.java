package de.mhus.vance.brain.tools.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.oauth.McpOAuthProvider;
import de.mhus.vance.brain.oauth.OAuthConfigRegistry;
import de.mhus.vance.brain.oauth.OAuthDisconnectedEvent;
import de.mhus.vance.brain.oauth.OAuthFlowService;
import de.mhus.vance.brain.oauth.OAuthProviderConfig;
import de.mhus.vance.brain.oauth.OAuthProviderLoader;
import de.mhus.vance.brain.oauth.OAuthStateService;
import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.brain.oauth.OAuthTokenStore;
import de.mhus.vance.brain.oauth.ResolvedOAuthProvider;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class McpOAuthServiceTest {

    private static final String TENANT = "acme";
    private static final String URL = "https://mcp.example.com/mcp";

    private OAuthConfigRegistry configRegistry;
    private OAuthTokenStore tokenStore;
    private McpAuthDiscoveryClient discovery;
    private DocumentService documentService;
    private SettingService settingService;
    private SettingsSecretResolver secretResolver;
    private ApplicationEventPublisher events;
    private McpOAuthService service;

    @BeforeEach
    void setUp() {
        configRegistry = mock(OAuthConfigRegistry.class);
        tokenStore = mock(OAuthTokenStore.class);
        discovery = mock(McpAuthDiscoveryClient.class);
        documentService = mock(DocumentService.class);
        settingService = mock(SettingService.class);
        secretResolver = mock(SettingsSecretResolver.class);
        events = mock(ApplicationEventPublisher.class);
        OAuthFlowService flow = new OAuthFlowService(mock(OAuthStateService.class), "http://localhost:9991");
        service = new McpOAuthService(
                configRegistry,
                flow,
                tokenStore,
                discovery,
                documentService,
                settingService,
                secretResolver,
                events,
                mock(ServerToolService.class));
    }

    private static McpOAuthSettings settings(Map<String, Object> auth) {
        return McpOAuthSettings.of(Map.of("transport", "http", "url", URL, "auth", auth))
                .orElseThrow();
    }

    private void discoveryAnswers(boolean cimd, String registrationEndpoint) {
        when(discovery.probe(eq(URL), any()))
                .thenReturn(new McpAuthDiscoveryClient.Challenge(true, "https://mcp.example.com/prm", List.of("read")));
        when(discovery.resourceMetadata(eq(URL), eq("https://mcp.example.com/prm"), any()))
                .thenReturn(new McpAuthDiscoveryClient.ResourceMetadata(
                        URL, List.of("https://auth.example.com"), List.of()));
        when(discovery.authServerMetadata(eq(URL), eq("https://auth.example.com"), any()))
                .thenReturn(new McpAuthDiscoveryClient.AuthServerMetadata(
                        "https://auth.example.com",
                        "https://auth.example.com/authorize",
                        "https://auth.example.com/token",
                        registrationEndpoint,
                        cimd,
                        List.of("S256"),
                        List.of()));
    }

    private ResolvedOAuthProvider resolvedProvider(String providerId) {
        return new ResolvedOAuthProvider(
                new OAuthProviderConfig(
                        providerId, McpOAuthProvider.TYPE_ID, null, "a", "t", "c", "", List.of(), Map.of()),
                new McpOAuthProvider());
    }

    @Test
    void ensureProvider_registersDynamicallyAndWritesLoadableYaml() {
        McpOAuthSettings s = settings(Map.of("type", "oauth"));
        discoveryAnswers(/*cimd*/ true, "https://auth.example.com/register");
        when(discovery.register(
                        eq(URL), eq("https://auth.example.com/register"), anyString(), anyList(), anyList(), any()))
                .thenReturn(new McpAuthDiscoveryClient.ClientRegistration("dyn-1", null));
        when(configRegistry.resolve(TENANT, s.providerId()))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(resolvedProvider(s.providerId())));
        when(documentService.findByPath(eq(TENANT), eq("_tenant"), anyString())).thenReturn(Optional.empty());

        service.ensureProvider(TENANT, s, "Notion", Map.of(), WriteActor.SYSTEM);

        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(documentService)
                .createText(
                        eq(TENANT),
                        eq("_tenant"),
                        eq(OAuthProviderLoader.pathFor(s.providerId())),
                        anyString(),
                        isNull(),
                        yaml.capture(),
                        any(),
                        eq(WriteActor.SYSTEM));
        // localhost is no public base URL — CIMD is skipped although supported
        assertThat(yaml.getValue())
                .contains("type: mcp")
                .contains("clientId: dyn-1")
                .contains("mcpUrl: " + URL)
                .contains("usePkce: true")
                .contains("registration: dcr")
                .contains("- read");
        OAuthProviderConfig parsed =
                new OAuthProviderLoader(documentService, settingService).validateYaml(s.providerId(), yaml.getValue());
        assertThat(parsed.authorizeUrl()).isEqualTo("https://auth.example.com/authorize");
        verify(configRegistry).refreshOne(TENANT, s.providerId());
        verify(settingService).delete(eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq("_tenant"), anyString());
    }

    @Test
    void ensureProvider_usesManualClientAndStoresSecret() {
        McpOAuthSettings s =
                settings(Map.of("type", "oauth", "clientId", "manual-1", "clientSecret", "{{secret:tenant:k}}"));
        discoveryAnswers(false, null);
        when(secretResolver.substituteForConnector(eq("{{secret:tenant:k}}"), any()))
                .thenReturn("s3cret");
        when(configRegistry.resolve(TENANT, s.providerId()))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(resolvedProvider(s.providerId())));
        when(documentService.findByPath(eq(TENANT), eq("_tenant"), anyString())).thenReturn(Optional.empty());

        service.ensureProvider(TENANT, s, "Manual", Map.of(), WriteActor.SYSTEM);

        verify(settingService)
                .setEncryptedPassword(
                        TENANT,
                        SettingService.SCOPE_PROJECT,
                        "_tenant",
                        OAuthProviderLoader.clientSecretKey(s.providerId()),
                        "s3cret");
        verify(discovery, never()).register(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ensureProvider_updatesExistingDocument() {
        McpOAuthSettings s = settings(Map.of("type", "oauth"));
        discoveryAnswers(false, "https://auth.example.com/register");
        when(discovery.register(any(), any(), any(), any(), any(), any()))
                .thenReturn(new McpAuthDiscoveryClient.ClientRegistration("dyn-2", "srv-secret"));
        when(configRegistry.resolve(TENANT, s.providerId()))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(resolvedProvider(s.providerId())));
        DocumentDocument existing = new DocumentDocument();
        existing.setId("doc-1");
        when(documentService.findByPath(eq(TENANT), eq("_tenant"), anyString())).thenReturn(Optional.of(existing));

        service.ensureProvider(TENANT, s, "X", Map.of(), WriteActor.SYSTEM);

        verify(documentService).update(eq("doc-1"), isNull(), isNull(), anyString(), isNull(), eq(WriteActor.SYSTEM));
        verify(settingService)
                .setEncryptedPassword(
                        eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq("_tenant"), anyString(), eq("srv-secret"));
    }

    @Test
    void ensureProvider_returnsExistingWithoutDiscovery() {
        McpOAuthSettings s = settings(Map.of("type", "oauth"));
        ResolvedOAuthProvider existing = resolvedProvider(s.providerId());
        when(configRegistry.resolve(TENANT, s.providerId())).thenReturn(Optional.of(existing));

        assertThat(service.ensureProvider(TENANT, s, "X", Map.of(), WriteActor.SYSTEM))
                .isSameAs(existing);
        verifyNoInteractions(discovery, documentService);
    }

    @Test
    void ensureProvider_failsForServerWithoutLogin() {
        McpOAuthSettings s = settings(Map.of("type", "oauth"));
        when(configRegistry.resolve(TENANT, s.providerId())).thenReturn(Optional.empty());
        when(discovery.probe(eq(URL), any())).thenReturn(new McpAuthDiscoveryClient.Challenge(false, null, List.of()));

        assertThatThrownBy(() -> service.ensureProvider(TENANT, s, "X", Map.of(), WriteActor.SYSTEM))
                .isInstanceOf(McpOAuthException.class)
                .hasMessageContaining("without login");
    }

    @Test
    void ensureProvider_failsWithoutAnyRegistration() {
        McpOAuthSettings s = settings(Map.of("type", "oauth"));
        when(configRegistry.resolve(TENANT, s.providerId())).thenReturn(Optional.empty());
        discoveryAnswers(false, null);

        assertThatThrownBy(() -> service.ensureProvider(TENANT, s, "X", Map.of(), WriteActor.SYSTEM))
                .isInstanceOf(McpOAuthException.class)
                .hasMessageContaining("auth.clientId");
    }

    @Test
    void status_reportsConnectedNotConnectedAndExpired() {
        McpOAuthSettings s = settings(Map.of("type", "oauth"));
        OAuthTokenOwner owner = OAuthTokenOwner.user("alice");
        String pid = s.providerId();

        when(tokenStore.isConnected(TENANT, owner, pid)).thenReturn(false);
        assertThat(service.status(TENANT, s, owner)).isEqualTo(McpOAuthService.STATUS_NOT_CONNECTED);

        when(tokenStore.isConnected(TENANT, owner, pid)).thenReturn(true);
        when(tokenStore.expiresAt(TENANT, owner, pid)).thenReturn("2000-01-01T00:00:00Z");
        assertThat(service.status(TENANT, s, owner)).isEqualTo(McpOAuthService.STATUS_EXPIRED);

        when(tokenStore.refreshToken(TENANT, owner, pid)).thenReturn("rt");
        assertThat(service.status(TENANT, s, owner)).isEqualTo(McpOAuthService.STATUS_CONNECTED);
    }

    @Test
    void disconnect_sharedLogin_publishesTenantWideEvent() {
        McpOAuthSettings s = settings(Map.of("type", "oauth", "loginScope", "project"));

        service.disconnect(TENANT, s, OAuthTokenOwner.project("p1"));

        verify(tokenStore).wipe(TENANT, OAuthTokenOwner.project("p1"), s.providerId());
        verify(events).publishEvent(new OAuthDisconnectedEvent(TENANT, null, s.providerId()));
    }

    @Test
    void clientMetadataDocument_namesMcpCallback() {
        Map<String, Object> doc = service.clientMetadataDocument(TENANT);

        assertThat(doc.get("client_id"))
                .isEqualTo("http://localhost:9991/brain/acme/oauth-client/client-metadata.json");
        assertThat(doc.get("redirect_uris"))
                .isEqualTo(List.of("http://localhost:9991/brain/acme/oauth-client/_mcp/callback"));
        assertThat(doc.get("token_endpoint_auth_method")).isEqualTo("none");
    }
}
