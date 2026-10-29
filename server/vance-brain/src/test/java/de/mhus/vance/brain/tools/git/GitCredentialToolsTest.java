package de.mhus.vance.brain.tools.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.workspace.GitCredentialKey;
import de.mhus.vance.shared.workspace.GitCredentialStore;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitCredentialToolsTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext("t", "p", "s", "proc", "u");
    private static final GitCredentialKey KEY = new GitCredentialKey("mhus", "github.com");
    private static final Map<String, Object> PARAMS = Map.of("host", "GitHub.com", "user", "mhus");

    private GitCredentialStore store;
    private GitCredentialTools support;
    private GitLoginTool login;
    private GitLogoutTool logout;

    @BeforeEach
    void setUp() {
        store = mock(GitCredentialStore.class);
        support = mock(GitCredentialTools.class);
        when(support.enforce(any(), any())).thenReturn("p");
        login = new GitLoginTool(store, support);
        logout = new GitLogoutTool(store, support);
    }

    @Test
    void loginConfirmsTheKeyWithoutRevealingTheToken() {
        var located = new GitCredentialStore.Located(KEY, "p");
        when(store.locate("t", "p", KEY)).thenReturn(Optional.of(located));
        when(store.token("t", located)).thenReturn("s3cret-token");

        Map<String, Object> out = login.invoke(PARAMS, CTX);

        assertThat(out)
                .containsEntry("key", "credential.git.mhus@github.com")
                .containsEntry("scope", "project")
                .containsEntry("ready", true);
        assertThat(out.toString()).doesNotContain("s3cret-token");
        verify(support).enforce(CTX, Action.READ);
    }

    @Test
    void loginWithoutAStoredKeyTellsTheModelWhatToDo() {
        when(store.locate("t", "p", KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> login.invoke(PARAMS, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("credential.git.mhus@github.com not found")
                .hasMessageContaining("call git_login again");
    }

    @Test
    void loginRefusesAKeyThatCannotBeRead() {
        var located = new GitCredentialStore.Located(KEY, "_tenant");
        when(store.locate("t", "p", KEY)).thenReturn(Optional.of(located));
        when(store.token("t", located)).thenReturn(null);

        assertThatThrownBy(() -> login.invoke(PARAMS, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("cannot be read");
    }

    @Test
    void hostAndUserAreRequired() {
        assertThatThrownBy(() -> login.invoke(Map.of("host", "github.com"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'user'");
    }

    @Test
    void logoutRevokesTheProjectsCredential() {
        when(store.locate("t", "p", KEY)).thenReturn(Optional.of(new GitCredentialStore.Located(KEY, "p")));
        when(store.revoke("t", "p", KEY)).thenReturn(true);

        assertThat(logout.invoke(PARAMS, CTX)).containsEntry("revoked", true);

        verify(support).enforce(CTX, Action.WRITE);
    }

    @Test
    void logoutLeavesTheTenantWideCredentialToAnAdministrator() {
        when(store.locate("t", "p", KEY)).thenReturn(Optional.of(new GitCredentialStore.Located(KEY, "_tenant")));

        assertThatThrownBy(() -> logout.invoke(PARAMS, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tenant-wide");
        verify(store, never()).revoke(any(), any(), any());
    }

    @Test
    void logoutOfAnUnknownKeyIsAnError() {
        when(store.locate("t", "p", KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> logout.invoke(PARAMS, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nothing to revoke");
    }
}
