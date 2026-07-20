package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RemoteComposeGitTest {

    @Test
    void importRepo_runsCloneOrPullCommandOnExecBackend() {
        ComposeExec exec = mock(ComposeExec.class);
        when(exec.run(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(new ComposeExec.Result("COMPLETED", 0, "", ""));

        new RemoteComposeGit(exec, "CLIENT")
                .importRepo("https://example.com/r.git", "repo", "main", null);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(exec).run(cmd.capture(), anyInt());
        assertThat(cmd.getValue())
                .contains("git clone --branch 'main' 'https://example.com/r.git' 'repo'");
    }

    @Test
    void importRepo_nonZeroExit_failsRun() {
        ComposeExec exec = mock(ComposeExec.class);
        when(exec.run(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(new ComposeExec.Result("COMPLETED", 127, "git: not found", ""));

        assertThatThrownBy(() -> new RemoteComposeGit(exec, "CLIENT")
                .importRepo("https://example.com/r.git", "repo", null, null))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("exit code 127");
    }

    @Test
    void credentialAlias_isRejectedAsWorkOnly_withoutRunningGit() {
        ComposeExec exec = mock(ComposeExec.class);

        assertThatThrownBy(() -> new RemoteComposeGit(exec, "DAEMON")
                .exportRepo("repo", "https://example.com/r.git", null, "msg", true, "gh"))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("credentialAlias");
        verify(exec, never()).run(eq("gh"), anyInt());
        verify(exec, never()).run(org.mockito.ArgumentMatchers.any(), anyInt());
    }
}
