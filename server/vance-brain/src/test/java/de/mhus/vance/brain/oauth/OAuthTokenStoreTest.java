package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
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
}
