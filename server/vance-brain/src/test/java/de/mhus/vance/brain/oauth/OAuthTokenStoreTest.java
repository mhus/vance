package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuthTokenStoreTest {

    private static final String TENANT = "acme";

    private SettingService settings;
    private OAuthTokenStore store;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        store = new OAuthTokenStore(settings);
    }

    @Test
    void projectOwner_readsAndWritesOnTheProject() {
        OAuthTokenOwner owner = OAuthTokenOwner.project("team");
        when(settings.getDecryptedPassword(TENANT, SettingService.SCOPE_PROJECT, "team", "oauth.mcp-x.access_token"))
                .thenReturn("at");

        assertThat(store.accessToken(TENANT, owner, "mcp-x")).isEqualTo("at");

        store.persist(TENANT, owner, "mcp-x", new OAuthTokenSet("new-at", null, null, Map.of()), true);
        verify(settings)
                .setEncryptedPassword(
                        TENANT, SettingService.SCOPE_PROJECT, "team", "oauth.mcp-x.access_token", "new-at");
        verify(settings).delete(TENANT, SettingService.SCOPE_PROJECT, "team", "oauth.mcp-x.refresh_token");
    }

    @Test
    void userOwner_readsThroughUserSettings() {
        when(settings.getDecryptedUserPassword(TENANT, "alice", "oauth.slack.access_token"))
                .thenReturn("at");

        assertThat(store.isConnected(TENANT, OAuthTokenOwner.user("alice"), "slack"))
                .isTrue();
    }

    @Test
    void refreshPersist_keepsAbsentRefreshToken() {
        store.persist(TENANT, OAuthTokenOwner.tenant(), "mcp-x", new OAuthTokenSet("at", null, null, Map.of()), false);

        verify(settings, never()).delete(any(), any(), any(), eq("oauth.mcp-x.refresh_token"));
        verify(settings)
                .setEncryptedPassword(
                        TENANT, SettingService.SCOPE_PROJECT, "_tenant", "oauth.mcp-x.access_token", "at");
    }

    @Test
    void ownerOfStorageProject_recognisesKinds() {
        assertThat(OAuthTokenOwner.ofStorageProject("_tenant").kind()).isEqualTo(OAuthTokenOwner.Kind.TENANT);
        assertThat(OAuthTokenOwner.ofStorageProject("_user_alice")).isEqualTo(OAuthTokenOwner.user("alice"));
        assertThat(OAuthTokenOwner.ofStorageProject("team")).isEqualTo(OAuthTokenOwner.project("team"));
    }

    @Test
    void wipe_ofTenantLogin_keepsTheClientSecret() {
        // The tenant login's tokens share oauth.<id>.* on _tenant with the
        // provider's client secret — logout must not break the next login.
        when(settings.findAll(TENANT, SettingService.SCOPE_PROJECT, "_tenant"))
                .thenReturn(List.of(
                        setting("oauth.mcp-x.access_token"),
                        setting("oauth.mcp-x.refresh_token"),
                        setting("oauth.mcp-x.expires_at"),
                        setting("oauth.mcp-x.client_secret"),
                        setting("oauth.mcp-y.access_token"),
                        setting("unrelated.key")));

        long removed = store.wipe(TENANT, OAuthTokenOwner.tenant(), "mcp-x");

        assertThat(removed).isEqualTo(3);
        verify(settings).delete(TENANT, SettingService.SCOPE_PROJECT, "_tenant", "oauth.mcp-x.access_token");
        verify(settings).delete(TENANT, SettingService.SCOPE_PROJECT, "_tenant", "oauth.mcp-x.refresh_token");
        verify(settings).delete(TENANT, SettingService.SCOPE_PROJECT, "_tenant", "oauth.mcp-x.expires_at");
        verify(settings, never()).delete(TENANT, SettingService.SCOPE_PROJECT, "_tenant", "oauth.mcp-x.client_secret");
        verify(settings, never()).delete(TENANT, SettingService.SCOPE_PROJECT, "_tenant", "oauth.mcp-y.access_token");
        verify(settings, never()).deleteByPrefix(any(), any(), any(), any());
    }

    @Test
    void persist_neverProjectsReservedClaims() {
        // A token response is foreign input: a claim named client_secret must
        // not overwrite the client registration next to a tenant login.
        store.persist(
                TENANT,
                OAuthTokenOwner.tenant(),
                "mcp-x",
                new OAuthTokenSet("at", null, null, Map.of("client_secret", "evil", "cloud_id", "c-1")),
                true);

        verify(settings, never()).setEncryptedPassword(any(), any(), any(), eq("oauth.mcp-x.client_secret"), any());
        verify(settings, never()).set(any(), any(), any(), eq("oauth.mcp-x.client_secret"), any(), any(), any());
        verify(settings)
                .set(
                        eq(TENANT),
                        eq(SettingService.SCOPE_PROJECT),
                        eq("_tenant"),
                        eq("oauth.mcp-x.cloud_id"),
                        eq("c-1"),
                        any(),
                        any());
    }

    @Test
    void projectOwner_refusesTheTenantProject() {
        assertThatThrownBy(() -> OAuthTokenOwner.project("_tenant")).isInstanceOf(IllegalArgumentException.class);
    }

    private static SettingDocument setting(String key) {
        SettingDocument doc = new SettingDocument();
        doc.setKey(key);
        return doc;
    }
}
