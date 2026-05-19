package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.oauth.OAuthProviderListEntry;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.SubjectType;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-logic tests for {@link OAuthController} — covers list /
 * init / callback / disconnect. Collaborators are mocked; this is not
 * a full MockMvc integration.
 */
class OAuthControllerTest {

    private static final String TENANT = "acme";
    private static final String USERNAME = "wile.coyote";
    private static final String PROVIDER_ID = "slack";
    private static final String PUBLIC_BASE = "https://vance.example.com";
    private static final String EXPECTED_REDIRECT_URI =
            "https://vance.example.com/brain/acme/oauth/slack/callback";

    private OAuthConfigRegistry configRegistry;
    private OAuthStateService stateService;
    private SettingService settingService;
    private RequestAuthority authority;
    private OAuthController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        configRegistry = mock(OAuthConfigRegistry.class);
        stateService = mock(OAuthStateService.class);
        settingService = mock(SettingService.class);
        authority = mock(RequestAuthority.class);
        when(authority.contextOf(any(HttpServletRequest.class)))
                .thenReturn(new SecurityContext(SubjectType.USER, USERNAME, TENANT, List.of()));
        controller = new OAuthController(
                configRegistry, stateService, settingService, authority, PUBLIC_BASE);
        request = mock(HttpServletRequest.class);
    }

    // ─────── /providers ───────

    @Test
    void providers_returns_entries_with_connected_flag() {
        when(configRegistry.list(TENANT)).thenReturn(List.of(
                resolved("slack", "slack"),
                resolved("keycloak", "oidc")));
        when(settingService.getDecryptedUserPassword(eq(TENANT), eq(USERNAME),
                eq("oauth.slack.access_token"))).thenReturn("token-slack");
        when(settingService.getDecryptedUserPassword(eq(TENANT), eq(USERNAME),
                eq("oauth.keycloak.access_token"))).thenReturn(null);

        List<OAuthProviderListEntry> out = controller.listProviders(TENANT, request);

        assertThat(out).extracting(OAuthProviderListEntry::getProviderId)
                .containsExactly("keycloak", "slack");
        assertThat(out).filteredOn(e -> "slack".equals(e.getProviderId()))
                .extracting(OAuthProviderListEntry::isConnected)
                .containsExactly(true);
        assertThat(out).filteredOn(e -> "keycloak".equals(e.getProviderId()))
                .extracting(OAuthProviderListEntry::isConnected)
                .containsExactly(false);
    }

    // ─────── /init ───────

    @Test
    void init_redirects_to_provider_authorize_uri() {
        URI authorizeUri = URI.create("https://slack.com/oauth/v2/authorize?state=S123");
        RecordingProvider provider = new RecordingProvider(authorizeUri);
        when(configRegistry.resolve(TENANT, PROVIDER_ID))
                .thenReturn(Optional.of(new ResolvedOAuthProvider(
                        configWithSecret(PROVIDER_ID, "slack"), provider)));
        when(stateService.start(eq(TENANT), eq(USERNAME), eq(PROVIDER_ID), eq("/settings")))
                .thenReturn("S123");

        ResponseEntity<Void> resp = controller.init(TENANT, PROVIDER_ID, "/settings", request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation()).isEqualTo(authorizeUri);
        assertThat(provider.captured).hasSize(1);
        OAuthInitContext ctx = provider.captured.get(0);
        assertThat(ctx.tenantId()).isEqualTo(TENANT);
        assertThat(ctx.userId()).isEqualTo(USERNAME);
        assertThat(ctx.state()).isEqualTo("S123");
        assertThat(ctx.redirectUri()).isEqualTo(EXPECTED_REDIRECT_URI);
        assertThat(ctx.returnTo()).isEqualTo("/settings");
    }

    @Test
    void init_returns_404_when_provider_unknown() {
        when(configRegistry.resolve(TENANT, PROVIDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.init(TENANT, PROVIDER_ID, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No OAuth provider");
        verify(stateService, never()).start(any(), any(), any(), any());
    }

    @Test
    void init_rejects_when_client_secret_missing() {
        OAuthProviderConfig noSecret = new OAuthProviderConfig(
                PROVIDER_ID, "slack", null,
                "https://slack.com/oauth/v2/authorize",
                "https://slack.com/api/oauth.v2.access",
                "client-id",
                /*clientSecret*/ "",
                new ArrayList<>(), new LinkedHashMap<>());
        when(configRegistry.resolve(TENANT, PROVIDER_ID))
                .thenReturn(Optional.of(new ResolvedOAuthProvider(noSecret, new RecordingProvider(null))));

        assertThatThrownBy(() -> controller.init(TENANT, PROVIDER_ID, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no client secret");
    }

    // ─────── /callback ───────

    @Test
    void callback_persists_tokens_and_redirects_to_returnTo() {
        URI authorizeUri = URI.create("https://provider.example/auth");
        OAuthTokenSet tokens = new OAuthTokenSet(
                "access-1",
                "refresh-1",
                Instant.parse("2026-05-19T13:00:00Z"),
                Map.of("scope", "channels:read chat:write", "team_id", "T123"));
        RecordingProvider provider = new RecordingProvider(authorizeUri, tokens);
        when(configRegistry.resolve(TENANT, PROVIDER_ID))
                .thenReturn(Optional.of(new ResolvedOAuthProvider(
                        configWithSecret(PROVIDER_ID, "slack"), provider)));
        when(stateService.consume(eq("S123"), eq(TENANT), eq(USERNAME)))
                .thenReturn(Optional.of(new OAuthStateService.Consumed(PROVIDER_ID, "/settings")));

        ResponseEntity<Void> resp = controller.callback(
                TENANT, PROVIDER_ID, "auth-code", "S123", request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation()).isEqualTo(URI.create("/settings"));

        verify(settingService).setEncryptedPassword(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq("_user_" + USERNAME),
                eq("oauth.slack.access_token"), eq("access-1"));
        verify(settingService).setEncryptedPassword(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq("_user_" + USERNAME),
                eq("oauth.slack.refresh_token"), eq("refresh-1"));
        verify(settingService).set(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq("_user_" + USERNAME),
                eq("oauth.slack.expires_at"),
                eq("2026-05-19T13:00:00Z"),
                eq(SettingType.STRING), any());
        verify(settingService).set(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq("_user_" + USERNAME),
                eq("oauth.slack.scopes"),
                eq("channels:read chat:write"),
                eq(SettingType.STRING), any());
        verify(settingService).set(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq("_user_" + USERNAME),
                eq("oauth.slack.extra"),
                anyString(), eq(SettingType.STRING), any());
    }

    @Test
    void callback_redirects_to_root_when_returnTo_absent() {
        when(configRegistry.resolve(TENANT, PROVIDER_ID))
                .thenReturn(Optional.of(new ResolvedOAuthProvider(
                        configWithSecret(PROVIDER_ID, "slack"),
                        new RecordingProvider(null,
                                new OAuthTokenSet("a", null, null, Map.of())))));
        when(stateService.consume(eq("S123"), eq(TENANT), eq(USERNAME)))
                .thenReturn(Optional.of(new OAuthStateService.Consumed(PROVIDER_ID, null)));

        ResponseEntity<Void> resp = controller.callback(
                TENANT, PROVIDER_ID, "code", "S123", request);

        assertThat(resp.getHeaders().getLocation()).isEqualTo(URI.create("/"));
    }

    @Test
    void callback_rejects_open_redirect_returnTo() {
        when(configRegistry.resolve(TENANT, PROVIDER_ID))
                .thenReturn(Optional.of(new ResolvedOAuthProvider(
                        configWithSecret(PROVIDER_ID, "slack"),
                        new RecordingProvider(null,
                                new OAuthTokenSet("a", null, null, Map.of())))));
        when(stateService.consume(eq("S123"), eq(TENANT), eq(USERNAME)))
                .thenReturn(Optional.of(new OAuthStateService.Consumed(
                        PROVIDER_ID, "https://evil.com/steal")));

        ResponseEntity<Void> resp = controller.callback(
                TENANT, PROVIDER_ID, "code", "S123", request);

        // Open-redirect protection — non-same-origin returnTo gets dropped.
        assertThat(resp.getHeaders().getLocation()).isEqualTo(URI.create("/"));
    }

    @Test
    void callback_400_on_invalid_state() {
        when(stateService.consume(eq("bad"), eq(TENANT), eq(USERNAME)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.callback(TENANT, PROVIDER_ID, "code", "bad", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid or expired");
        verify(settingService, never()).setEncryptedPassword(any(), any(), any(), any(), any());
    }

    @Test
    void callback_400_on_provider_id_mismatch() {
        when(stateService.consume(eq("S123"), eq(TENANT), eq(USERNAME)))
                .thenReturn(Optional.of(new OAuthStateService.Consumed("github", null)));

        assertThatThrownBy(() -> controller.callback(TENANT, "slack", "code", "S123", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not match");
        verify(settingService, never()).setEncryptedPassword(any(), any(), any(), any(), any());
    }

    @Test
    void callback_drops_stale_refresh_token_when_provider_omits_one() {
        when(configRegistry.resolve(TENANT, PROVIDER_ID))
                .thenReturn(Optional.of(new ResolvedOAuthProvider(
                        configWithSecret(PROVIDER_ID, "slack"),
                        new RecordingProvider(null,
                                new OAuthTokenSet("a", /*refresh*/ null, null, Map.of())))));
        when(stateService.consume(eq("S123"), eq(TENANT), eq(USERNAME)))
                .thenReturn(Optional.of(new OAuthStateService.Consumed(PROVIDER_ID, null)));

        controller.callback(TENANT, PROVIDER_ID, "code", "S123", request);

        verify(settingService).delete(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq("_user_" + USERNAME),
                eq("oauth.slack.refresh_token"));
    }

    @Test
    void callback_502_when_provider_throws_flow_exception() {
        RecordingProvider failing = new RecordingProvider(null) {
            @Override
            public OAuthTokenSet exchangeCode(OAuthProviderConfig c, String code, OAuthInitContext ctx) {
                throw new OAuthFlowException(PROVIDER_ID, "invalid_grant");
            }
        };
        when(configRegistry.resolve(TENANT, PROVIDER_ID))
                .thenReturn(Optional.of(new ResolvedOAuthProvider(
                        configWithSecret(PROVIDER_ID, "slack"), failing)));
        when(stateService.consume(eq("S123"), eq(TENANT), eq(USERNAME)))
                .thenReturn(Optional.of(new OAuthStateService.Consumed(PROVIDER_ID, null)));

        assertThatThrownBy(() -> controller.callback(TENANT, PROVIDER_ID, "code", "S123", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rejected token exchange");
    }

    // ─────── /disconnect ───────

    @Test
    void disconnect_deletes_all_user_settings_for_provider() {
        ResponseEntity<Void> resp = controller.disconnect(TENANT, PROVIDER_ID, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(settingService, times(1)).delete(TENANT, SettingService.SCOPE_PROJECT,
                "_user_" + USERNAME, "oauth.slack.access_token");
        verify(settingService, times(1)).delete(TENANT, SettingService.SCOPE_PROJECT,
                "_user_" + USERNAME, "oauth.slack.refresh_token");
        verify(settingService, times(1)).delete(TENANT, SettingService.SCOPE_PROJECT,
                "_user_" + USERNAME, "oauth.slack.expires_at");
        verify(settingService, times(1)).delete(TENANT, SettingService.SCOPE_PROJECT,
                "_user_" + USERNAME, "oauth.slack.scopes");
        verify(settingService, times(1)).delete(TENANT, SettingService.SCOPE_PROJECT,
                "_user_" + USERNAME, "oauth.slack.extra");
    }

    // ─────── Helpers ───────

    private static ResolvedOAuthProvider resolved(String providerId, String typeId) {
        return new ResolvedOAuthProvider(
                configWithSecret(providerId, typeId),
                new RecordingProvider(URI.create("https://stub/auth")));
    }

    private static OAuthProviderConfig configWithSecret(String providerId, String typeId) {
        return new OAuthProviderConfig(
                providerId, typeId,
                /*discoveryUrl*/ null,
                /*authorizeUrl*/ "https://provider.example/authorize",
                /*tokenUrl*/ "https://provider.example/token",
                "client-id-" + providerId,
                "client-secret-" + providerId,
                new ArrayList<>(),
                new LinkedHashMap<>());
    }

    private static class RecordingProvider implements OAuthProvider {
        final URI authorizeUri;
        final OAuthTokenSet tokens;
        final List<OAuthInitContext> captured = new ArrayList<>();

        RecordingProvider(URI authorizeUri) {
            this(authorizeUri, new OAuthTokenSet("a", null, null, Map.of()));
        }

        RecordingProvider(URI authorizeUri, OAuthTokenSet tokens) {
            this.authorizeUri = authorizeUri;
            this.tokens = tokens;
        }

        @Override public String typeId() { return "stub"; }

        @Override public URI buildAuthorizeUri(OAuthProviderConfig c, OAuthInitContext ctx) {
            captured.add(ctx);
            return authorizeUri;
        }

        @Override public OAuthTokenSet exchangeCode(OAuthProviderConfig c, String code, OAuthInitContext ctx) {
            return tokens;
        }

        @Override public OAuthTokenSet refresh(OAuthProviderConfig c, String refreshToken) {
            return tokens;
        }
    }
}
