package de.mhus.vance.brain.tools.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.workspace.GitAuthProvider;
import de.mhus.vance.shared.workspace.GitCredentialException;
import de.mhus.vance.toolpack.ToolException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;

class GitCredentialResolverTest {

    private final GitAuthProvider provider = mock(GitAuthProvider.class);
    private final GitCredentialResolver resolver =
            new GitCredentialResolver(provider, new RunnerRepoUrl(false, url -> {}));

    @Test
    void theHostOfTheUrlSelectsTheCredential() {
        when(provider.provide("acme", "p", "git.example.com", null))
                .thenReturn(new UsernamePasswordCredentialsProvider("bot", "s3cret"));

        var resolved = resolver.resolve("acme", "p", "https://git.example.com/r.git", false);

        assertThat(resolved).containsEntry("username", "bot").containsEntry("token", "s3cret");
    }

    @Test
    void theAccountInTheUrlPicksAmongSeveral() {
        when(provider.provide("acme", "p", "github.com", "alice"))
                .thenReturn(new UsernamePasswordCredentialsProvider("alice", "tok-a"));

        assertThat(resolver.resolve("acme", "p", "https://alice@github.com/o/r.git", false))
                .containsEntry("token", "tok-a");
    }

    @Test
    void aHostWithoutACredentialIsPublic() {
        when(provider.provide("acme", "p", "github.com", null)).thenReturn(null);

        assertThat(resolver.resolve("acme", "p", "https://github.com/o/r.git", false))
                .isNull();
    }

    @Test
    void publicSkipsTheStoreAltogether() {
        assertThat(resolver.resolve("acme", "p", "https://github.com/o/r.git", true))
                .isNull();

        verify(provider, never()).provide(any(), any(), any(), any());
    }

    @Test
    void severalAccountsWithoutAPickAreReportedNotGuessed() {
        when(provider.provide("acme", "p", "github.com", null))
                .thenThrow(new GitCredentialException("github.com has credentials for alice and bob"));

        assertThatThrownBy(() -> resolver.resolve("acme", "p", "https://github.com/o/r.git", false))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("alice and bob");
    }

    @Test
    void aStoredCredentialIsNeverSentOverPlainHttp() {
        when(provider.provide("acme", "p", "git.example.com", null))
                .thenReturn(new UsernamePasswordCredentialsProvider("bot", "s3cret"));

        assertThatThrownBy(() -> resolver.resolve("acme", "p", "http://git.example.com/r.git", false))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("https");
    }

    @Test
    void aProviderThatCannotOfferAUsernameAndTokenIsRefused() {
        CredentialsProvider unusable = new CredentialsProvider() {
            @Override
            public boolean isInteractive() {
                return false;
            }

            @Override
            public boolean supports(org.eclipse.jgit.transport.CredentialItem... items) {
                return false;
            }

            @Override
            public boolean get(
                    org.eclipse.jgit.transport.URIish uri, org.eclipse.jgit.transport.CredentialItem... items) {
                return false;
            }
        };
        when(provider.provide("acme", "p", "git.example.com", null)).thenReturn(unusable);

        assertThatThrownBy(() -> resolver.resolve("acme", "p", "https://git.example.com/r.git", false))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no username and token");
    }
}
