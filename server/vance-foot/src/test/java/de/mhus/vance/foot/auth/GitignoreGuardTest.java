package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitignoreGuardTest {

    private final GitignoreGuard guard = new GitignoreGuard();

    private Path repoWithVance(Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Path vance = root.resolve(".vance");
        Files.createDirectories(vance);
        return vance;
    }

    @Test
    void noGitRepo_reportsNoGit(@TempDir Path root) throws Exception {
        Path vance = root.resolve(".vance");
        Files.createDirectories(vance);

        GitignoreGuard.Result result = guard.ensureAccessIgnored(vance);

        assertThat(result.kind()).isEqualTo(GitignoreGuard.Kind.NO_GIT);
    }

    @Test
    void notIgnored_appendsAccessEntry(@TempDir Path root) throws Exception {
        Path vance = repoWithVance(root);

        GitignoreGuard.Result result = guard.ensureAccessIgnored(vance);

        assertThat(result.kind()).isEqualTo(GitignoreGuard.Kind.ADDED);
        assertThat(result.entry()).isEqualTo(".vance/access.yaml");
        assertThat(Files.readString(root.resolve(".gitignore")))
                .contains(".vance/access.yaml");
    }

    @Test
    void appendKeepsExistingContentAndAddsNewline(@TempDir Path root) throws Exception {
        Path vance = repoWithVance(root);
        Files.writeString(root.resolve(".gitignore"), "target/"); // no trailing newline

        guard.ensureAccessIgnored(vance);

        String gi = Files.readString(root.resolve(".gitignore"));
        assertThat(gi).isEqualTo("target/\n.vance/access.yaml\n");
    }

    @Test
    void alreadyIgnoredByFileEntry_noChange(@TempDir Path root) throws Exception {
        Path vance = repoWithVance(root);
        Files.writeString(root.resolve(".gitignore"), ".vance/access.yaml\n");

        GitignoreGuard.Result result = guard.ensureAccessIgnored(vance);

        assertThat(result.kind()).isEqualTo(GitignoreGuard.Kind.ALREADY_IGNORED);
    }

    @Test
    void alreadyIgnoredByDirEntry_noChange(@TempDir Path root) throws Exception {
        Path vance = repoWithVance(root);
        Files.writeString(root.resolve(".gitignore"), "# secrets\n.vance/\n");

        GitignoreGuard.Result result = guard.ensureAccessIgnored(vance);

        assertThat(result.kind()).isEqualTo(GitignoreGuard.Kind.ALREADY_IGNORED);
    }

    @Test
    void findsGitRootFromNestedDirectory(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Path vance = root.resolve("sub").resolve(".vance");
        Files.createDirectories(vance);

        GitignoreGuard.Result result = guard.ensureAccessIgnored(vance);

        assertThat(result.kind()).isEqualTo(GitignoreGuard.Kind.ADDED);
        assertThat(result.entry()).isEqualTo("sub/.vance/access.yaml");
    }
}
