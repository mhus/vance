package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.workspace.GitAuthProvider;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Security regression (code-review-2 S2): a manifest-authored git remote must
 * not let jgit clone {@code file://} (local-file disclosure) or {@code git://}
 * (unauthenticated internal probe) URLs. Only http(s) (SSRF-guarded) + ssh are
 * permitted, validated before jgit ever sees the URL.
 */
class GitServiceTest {

    private final GitService git = new GitService(mock(GitAuthProvider.class));

    @Test
    void cloneOrPull_rejectsFileScheme() {
        assertThatThrownBy(() -> git.cloneOrPull(
                Path.of("/tmp/vance-gitservice-test-x"), "file:///var/lib/vance/other/.git",
                null, "acme", "projA", null))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void cloneOrPull_rejectsGitProtocol() {
        assertThatThrownBy(() -> git.cloneOrPull(
                Path.of("/tmp/vance-gitservice-test-y"), "git://internal-service:9418/repo",
                null, "acme", "projA", null))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("not allowed");
    }
}
