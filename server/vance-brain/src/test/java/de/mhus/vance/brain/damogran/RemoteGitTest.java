package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemoteGitTest {

    @Test
    void isGit_detectsGitScheme() {
        assertThat(RemoteGit.isGit("git:https://example.com/r.git")).isTrue();
        assertThat(RemoteGit.isGit("vance:notes.md")).isFalse();
        assertThat(RemoteGit.isGit("https://example.com/x")).isFalse();
    }

    @Test
    void cloneOrPull_freshCloneOrFastForwardPull_isIdempotent() {
        String cmd = RemoteGit.cloneOrPullCommand("https://example.com/r.git", "repo", null);

        assertThat(cmd)
                .contains("if [ -d 'repo'/.git ]")
                .contains("git -C 'repo' pull --ff-only")
                .contains("git clone 'https://example.com/r.git' 'repo'");
    }

    @Test
    void cloneOrPull_withBranch_passesBranchToClone() {
        String cmd = RemoteGit.cloneOrPullCommand("https://example.com/r.git", "repo", "dev");

        assertThat(cmd).contains("git clone --branch 'dev' 'https://example.com/r.git' 'repo'");
    }

    @Test
    void commitPush_commitsOnlyWhenStagedAndPushesHead() {
        String cmd = RemoteGit.commitPushCommand(
                "repo", "https://example.com/r.git", null, "my msg", true);

        assertThat(cmd)
                .contains("git -C 'repo' add -A")
                .contains("if ! git -C 'repo' diff --cached --quiet; then")
                .contains("git -C 'repo' commit -m 'my msg'")
                .contains("remote set-url origin 'https://example.com/r.git'")
                .contains("git -C 'repo' push origin 'HEAD'");
    }

    @Test
    void commitPush_withBranch_pushesHeadToBranchRefspec() {
        String cmd = RemoteGit.commitPushCommand(
                "repo", "https://example.com/r.git", "main", "msg", true);

        assertThat(cmd).contains("git -C 'repo' push origin 'HEAD:main'");
    }

    @Test
    void commitPush_pushFalse_stagesAndCommitsButDoesNotPush() {
        String cmd = RemoteGit.commitPushCommand(
                "repo", "https://example.com/r.git", null, "msg", false);

        assertThat(cmd)
                .contains("git -C 'repo' commit -m 'msg'")
                .doesNotContain("push");
    }

    @Test
    void sh_escapesEmbeddedSingleQuotes_soMetacharactersStayLiteral() {
        // A path/message with a quote and shell metacharacters must not break out.
        String quoted = RemoteGit.sh("a'b; rm -rf /");

        assertThat(quoted).isEqualTo("'a'\\''b; rm -rf /'");
    }
}
