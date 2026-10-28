package de.mhus.vance.brain.tools.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.workspace.GitAuthProvider;
import de.mhus.vance.toolpack.ToolException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;

class GitCredentialResolverTest {

    private final GitAuthProvider provider = mock(GitAuthProvider.class);
    private final GitCredentialResolver resolver = new GitCredentialResolver(provider);

    @Test
    void noAliasMeansNoCredentials() {
        assertThat(resolver.resolve("acme", "p", null, "https://git.example.com/r.git"))
                .isNull();
        assertThat(resolver.resolve("acme", "p", " ", "https://git.example.com/r.git"))
                .isNull();
    }

    @Test
    void anAliasResolvesToUsernameAndToken() {
        when(provider.provide("acme", "p", "ci")).thenReturn(new UsernamePasswordCredentialsProvider("bot", "s3cret"));

        var resolved = resolver.resolve("acme", "p", "ci", "https://git.example.com/r.git");

        assertThat(resolved).containsEntry("username", "bot").containsEntry("token", "s3cret");
    }

    @Test
    void anUnknownAliasIsAnErrorNotAnAnonymousClone() {
        when(provider.provide("acme", "p", "nope")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolve("acme", "p", "nope", "https://git.example.com/r.git"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Unknown git credential alias 'nope'");
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
        when(provider.provide("acme", "p", "odd")).thenReturn(unusable);

        assertThatThrownBy(() -> resolver.resolve("acme", "p", "odd", "https://git.example.com/r.git"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no username and token");
    }
}
