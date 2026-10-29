package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;

class SettingsGitAuthProviderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "p1";
    private static final String HUB = SettingService.SCOPE_PROJECT;

    private final SettingService settings = mock(SettingService.class);
    private final GitCredentialStore store = new GitCredentialStore(settings);
    private final SettingsGitAuthProvider provider = new SettingsGitAuthProvider(store);

    private static SettingDocument doc(String user, String host, SettingType type) {
        return SettingDocument.builder()
                .key(new GitCredentialKey(user, host).storageKey())
                .type(type)
                .build();
    }

    private void has(String referenceId, SettingDocument... docs) {
        when(settings.findByPrefix(TENANT, HUB, referenceId, GitCredentialKey.PREFIX))
                .thenReturn(List.of(docs));
    }

    private void token(String referenceId, String user, String host, String token) {
        when(settings.getDecryptedPassword(TENANT, HUB, referenceId, new GitCredentialKey(user, host).storageKey()))
                .thenReturn(token);
    }

    private static String[] usernameAndPassword(CredentialsProvider p) throws Exception {
        CredentialItem.Username user = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertThat(p.get(new URIish("https://github.com/o/r.git"), user, password))
                .isTrue();
        return new String[] {user.getValue(), new String(password.getValue())};
    }

    @Test
    void theHostSelectsTheOnlyCredential() throws Exception {
        has("_tenant", doc("bot", "github.com", SettingType.PASSWORD));
        token("_tenant", "bot", "github.com", "s3cret");

        assertThat(usernameAndPassword(provider.provide(TENANT, PROJECT, "github.com", null)))
                .containsExactly("bot", "s3cret");
    }

    @Test
    void aHostWithoutACredentialIsPublic() {
        assertThat(provider.provide(TENANT, PROJECT, "github.com", null)).isNull();
    }

    @Test
    void anotherHostsCredentialIsNeverOffered() {
        has("_tenant", doc("bot", "gitlab.com", SettingType.PASSWORD));

        assertThat(provider.provide(TENANT, PROJECT, "github.com", null)).isNull();
    }

    @Test
    void theProjectsOwnCredentialBeatsTheTenantDefaultForTheSameAccount() throws Exception {
        has("_tenant", doc("bot", "github.com", SettingType.PASSWORD));
        has(PROJECT, doc("bot", "github.com", SettingType.HIDDEN));
        token(PROJECT, "bot", "github.com", "project-token");

        assertThat(usernameAndPassword(provider.provide(TENANT, PROJECT, "github.com", null)))
                .containsExactly("bot", "project-token");
    }

    @Test
    void severalAccountsAreReportedUnlessTheUrlPicksOne() throws Exception {
        has(
                "_tenant",
                doc("alice", "github.com", SettingType.PASSWORD),
                doc("bob", "github.com", SettingType.PASSWORD));
        token("_tenant", "bob", "github.com", "bob-token");

        assertThatThrownBy(() -> provider.provide(TENANT, PROJECT, "github.com", null))
                .isInstanceOf(GitCredentialException.class)
                .hasMessageContaining("alice, bob")
                .hasMessageContaining("https://alice@github.com");
        assertThat(usernameAndPassword(provider.provide(TENANT, PROJECT, "github.com", "bob")))
                .containsExactly("bob", "bob-token");
        assertThat(provider.provide(TENANT, PROJECT, "github.com", "carol")).isNull();
    }

    @Test
    void aSettingThatIsNotEncryptedIsNoCredential() {
        has("_tenant", doc("bot", "github.com", SettingType.STRING));

        assertThat(provider.provide(TENANT, PROJECT, "github.com", null)).isNull();
    }

    @Test
    void anUnreadableTokenIsAnErrorNotASilentAnonymousClone() {
        has("_tenant", doc("bot", "github.com", SettingType.PASSWORD));
        token("_tenant", "bot", "github.com", null);

        assertThatThrownBy(() -> provider.provide(TENANT, PROJECT, "github.com", null))
                .isInstanceOf(GitCredentialException.class)
                .hasMessageContaining("credential.git.bot@github.com");
    }

    @Test
    void revokeRemovesOnlyTheProjectsOwnEntry() {
        GitCredentialKey key = new GitCredentialKey("bot", "github.com");
        when(settings.exists(TENANT, HUB, PROJECT, key.storageKey())).thenReturn(true);
        when(settings.exists(TENANT, HUB, "other", key.storageKey())).thenReturn(false);

        assertThat(store.revoke(TENANT, PROJECT, key)).isTrue();
        assertThat(store.revoke(TENANT, "other", key)).isFalse();

        verify(settings).delete(TENANT, HUB, PROJECT, key.storageKey());
        verify(settings, org.mockito.Mockito.never()).delete(eq(TENANT), eq(HUB), eq("other"), any());
    }
}
