package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
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

        new RemoteComposeGit(exec, "CLIENT").importRepo("https://example.com/r.git", "repo", "main", false);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(exec).run(cmd.capture(), anyInt());
        assertThat(cmd.getValue()).contains("git clone --branch 'main' 'https://example.com/r.git' 'repo'");
    }

    @Test
    void importRepo_nonZeroExit_failsRun() {
        ComposeExec exec = mock(ComposeExec.class);
        when(exec.run(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(new ComposeExec.Result("COMPLETED", 127, "git: not found", ""));

        assertThatThrownBy(() -> new RemoteComposeGit(exec, "CLIENT")
                        .importRepo("https://example.com/r.git", "repo", null, false))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("exit code 127");
    }

    @Test
    void thePublicFlagChangesNothingRemotely_theHostsOwnGitCredentialsApply() {
        ComposeExec exec = mock(ComposeExec.class);
        when(exec.run(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(new ComposeExec.Result("COMPLETED", 0, "", ""));

        new RemoteComposeGit(exec, "DAEMON").exportRepo("repo", "https://example.com/r.git", null, "msg", true, true);

        verify(exec).run(org.mockito.ArgumentMatchers.any(), anyInt());
    }
}
