package de.mhus.vance.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.exec.GitOperationException;
import de.mhus.vance.exec.GitOperations;
import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.ProcessSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code git} through the runner's launcher: real repositories, and what a token may and may not touch. */
class ProgramGitOperationsTest {

    private static final GitOperations.Scope SCOPE = new GitOperations.Scope("acme", "p", false);

    @TempDir
    Path tmp;

    private ProgramGitOperations realGit() {
        RunnerProcessLauncher launcher = new RunnerProcessLauncher(tmp.resolve("data"), null);
        return new ProgramGitOperations(() -> launcher, true);
    }

    /** A repository with one commit on main, as a clone source. */
    private Path remote() throws Exception {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        ProgramGitOperations git = realGit();
        git.init(remote);
        Files.writeString(remote.resolve("README.md"), "hello\n");
        commit(remote, "first");
        return remote;
    }

    @Test
    void aRepositoryIsClonedAndItsHeadRead() throws Exception {
        Path remote = remote();
        ProgramGitOperations git = realGit();
        Path target = Files.createDirectories(tmp.resolve("clone"));

        git.clone(remote.toString(), target, "main", null, SCOPE);

        assertThat(Files.readString(target.resolve("README.md"))).isEqualTo("hello\n");
        assertThat(git.head(target)).isEqualTo(git.head(remote)).hasSize(40);
    }

    @Test
    void aShallowCloneHasOneCommit() throws Exception {
        Path remote = remote();
        ProgramGitOperations git = realGit();
        Files.writeString(remote.resolve("two.txt"), "2");
        commit(remote, "second");
        Path target = Files.createDirectories(tmp.resolve("shallow"));

        // A local path ignores --depth with a warning; a file:// URL honours it.
        git.clone(remote.toUri().toString(), target, "main", 1, SCOPE);

        assertThat(Files.exists(target.resolve("two.txt"))).isTrue();
        assertThat(Files.exists(target.resolve(".git/shallow"))).isTrue();
    }

    @Test
    void failuresCarryGitsOwnMessage() throws Exception {
        ProgramGitOperations git = realGit();
        Path target = Files.createDirectories(tmp.resolve("nowhere"));

        assertThatThrownBy(() -> git.clone(tmp.resolve("does-not-exist").toString(), target, "main", null, SCOPE))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("git clone failed");
        assertThat(git.head(Files.createDirectories(tmp.resolve("empty")))).isNull();
    }

    // ───────────────────────── credentials ─────────────────────────

    /** Records what would be started and starts {@code true} instead. */
    private static final class Recording implements ProcessLauncher {
        final List<ProcessSpec> specs = new ArrayList<>();

        @Override
        public Process start(ProcessSpec spec) throws IOException {
            specs.add(spec);
            return new ProcessBuilder("true").start();
        }
    }

    @Test
    void aTokenReachesGitAsAHeaderScopedToTheHost_neverInArgv() throws Exception {
        Recording launcher = new Recording();
        ProgramGitOperations git = new ProgramGitOperations(() -> launcher, true);
        Path target = Files.createDirectories(tmp.resolve("t1"));

        ProgramGitOperations.withCredentials(new GitCredentials("bot", "s3cret-token"), () -> {
            git.clone("https://git.example.com/org/repo.git", target, "main", null, SCOPE);
            return null;
        });

        ProcessSpec spec = launcher.specs.getFirst();
        assertThat(String.join(" ", spec.command()))
                .doesNotContain("s3cret-token")
                .doesNotContain("Basic");
        assertThat(spec.environment()).containsEntry("GIT_CONFIG_KEY_0", "http.https://git.example.com/.extraHeader");
        String expected = Base64.getEncoder().encodeToString("bot:s3cret-token".getBytes());
        assertThat(spec.environment()).containsEntry("GIT_CONFIG_VALUE_0", "Authorization: Basic " + expected);
        assertThat(spec.environment()).containsEntry("GIT_TERMINAL_PROMPT", "0");
        assertThat(spec.network()).isTrue();
    }

    @Test
    void everyGitCallCarriesTheHardening_andAProductionRunnerClonesOverHttpOnly() throws Exception {
        Recording launcher = new Recording();
        ProgramGitOperations git = new ProgramGitOperations(() -> launcher, false);
        Path target = Files.createDirectories(tmp.resolve("t0"));

        git.clone("https://git.example.com/org/repo.git", target, "main", null, SCOPE);
        git.head(target);

        for (ProcessSpec spec : launcher.specs) {
            assertThat(spec.command())
                    .containsSequence("-c", "core.hooksPath=/dev/null")
                    .containsSequence("-c", "core.fsmonitor=false")
                    .containsSequence("-c", "credential.helper=")
                    .doesNotContain("protocol.file.allow=always");
            assertThat(spec.environment()).containsEntry("GIT_ALLOW_PROTOCOL", "https:http");
        }
        for (String url : List.of(tmp.toString(), "file:///etc", "ext::sh -c id", "git@example.com:o/r.git")) {
            assertThatThrownBy(() -> git.clone(url, target, "main", null, SCOPE))
                    .as(url)
                    .isInstanceOf(GitOperationException.class)
                    .hasMessageContaining("http(s)");
        }
        assertThat(launcher.specs).hasSize(2);
    }

    @Test
    void aTokenIsOfferedOnlyToHttpsHosts_andOnlyWhileTheCallRuns() throws Exception {
        Recording launcher = new Recording();
        ProgramGitOperations git = new ProgramGitOperations(() -> launcher, true);
        Path target = Files.createDirectories(tmp.resolve("t2"));
        GitCredentials token = new GitCredentials("bot", "tok");

        ProgramGitOperations.withCredentials(token, () -> {
            git.clone("git@example.com:org/repo.git", target, "main", null, SCOPE);
            git.clone("http://insecure.example.com/r.git", target, "main", null, SCOPE);
            git.clone(tmp.resolve("local").toString(), target, "main", null, SCOPE);
            return null;
        });
        // After the call nothing is bound any more.
        git.clone("https://git.example.com/org/repo.git", target, "main", null, SCOPE);

        for (ProcessSpec spec : launcher.specs) {
            assertThat(spec.environment()).doesNotContainKey("GIT_CONFIG_VALUE_0");
        }
        assertThat(launcher.specs).hasSize(4);
    }

    @Test
    void theCredentialsRecordNeverPrintsItsToken() {
        assertThat(new GitCredentials("bot", "s3cret-token").toString()).doesNotContain("s3cret-token");
    }

    /** Stages everything and commits it — test setup for a clone source. */
    private static void commit(Path repo, String message) throws Exception {
        runGit(repo, "add", "-A");
        runGit(repo, "-c", "user.name=Vance", "-c", "user.email=vance@localhost", "commit", "-m", message);
    }

    private static String runGit(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }
}
