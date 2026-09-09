package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-repo tests for the git RootDir handler: clone from a locally
 * created source repo (no network), branch-tip checkout vs.
 * {@code checkoutCommit} detach, and the failure path for a commit the
 * clone does not carry — the whole point of the commit param is that a
 * RootDir silently sitting on the wrong revision must be impossible.
 */
class GitHandlerTest {

    @TempDir
    Path tempDir;

    private GitHandler handler;
    private Path sourceRepo;

    @BeforeEach
    void setUp() throws Exception {
        handler = new GitHandler((tenantId, projectId, alias) -> null);
        sourceRepo = createSourceRepoWithTwoCommits();
    }

    @Test
    void init_withoutCheckout_commitLandsOnBranchTip() throws Exception {
        RevCommit tip = secondCommitSha();
        RootDirHandle handle = handle("plain", meta(null));

        handler.init(handle, null);

        assertThat(handle.getDescriptor().getMetadata()).containsEntry(GitHandler.META_COMMIT, tip.getName());
        assertThat(workingTreeContent(handle)).isEqualTo("second\n");
    }

    @Test
    void init_withCheckoutCommit_detachesToThatCommit() throws Exception {
        RevCommit first = firstCommitSha();
        RootDirHandle handle = handle("detached", meta(first.getName()));

        handler.init(handle, null);

        assertThat(handle.getDescriptor().getMetadata()).containsEntry(GitHandler.META_COMMIT, first.getName());
        assertThat(handle.getDescriptor().getMetadata())
                .containsEntry(GitHandler.META_CHECKOUT_COMMIT, first.getName());
        assertThat(workingTreeContent(handle)).isEqualTo("first\n");
    }

    @Test
    void init_withDepth_createsShallowClone() throws Exception {
        RootDirHandle handle = handle("shallow", meta(null));
        handle.getDescriptor().getMetadata().put(GitHandler.META_DEPTH, 1);

        handler.init(handle, null);

        // The .git/shallow marker is what makes a clone shallow — its
        // presence proves the depth metadata reached the CloneCommand.
        assertThat(handle.getPath().resolve(".git").resolve("shallow"))
                .as("a depth-1 clone must carry a .git/shallow marker")
                .exists();
        // History is really gone: a depth-1 clone logs exactly one commit.
        assertThat(commitCountOf(handle.getPath()))
                .as("a depth-1 clone must carry exactly one commit in its log")
                .isEqualTo(1);
        // The snapshot itself still works: tip content, recorded commit.
        assertThat(workingTreeContent(handle)).isEqualTo("second\n");
    }

    @Test
    void init_withUnknownCommit_failsInsteadOfSittingOnTheWrongRevision() throws Exception {
        RootDirHandle handle = handle("broken", meta("deadbeef"));

        assertThatThrownBy(() -> handler.init(handle, null))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("deadbeef");
    }

    // ──────────────────── helpers ────────────────────

    /**
     * Source repo with branch {@code main} and two commits: the first
     * writes {@code first\n}, the second overwrites with
     * {@code second\n}. Local clones need no credentials — the null
     * provider is the production default for anonymous access.
     */
    private Path createSourceRepoWithTwoCommits() throws Exception {
        Path dir = tempDir.resolve("source");
        try (Git git =
                Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            commit(git, dir, "first\n", "first commit");
            commit(git, dir, "second\n", "second commit");
        }
        return dir;
    }

    private static void commit(Git git, Path dir, String content, String message) throws Exception {
        Files.writeString(dir.resolve("note.txt"), content, StandardCharsets.UTF_8);
        git.add().addFilepattern(".").call();
        git.commit()
                .setMessage(message)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call();
    }

    private RevCommit firstCommitSha() throws Exception {
        return nthCommit(1);
    }

    private RevCommit secondCommitSha() throws Exception {
        return nthCommit(0);
    }

    private RevCommit nthCommit(int skip) throws Exception {
        try (Git git = Git.open(sourceRepo.toFile())) {
            Iterable<RevCommit> commits = git.log().call();
            RevCommit[] arr = new RevCommit[2];
            int i = 0;
            for (RevCommit c : commits) {
                if (i < arr.length) {
                    arr[i] = c;
                }
                i++;
            }
            return arr[skip];
        }
    }

    private Map<String, Object> meta(String checkoutCommit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GitHandler.META_REPO_URL, sourceRepo.toUri().toString());
        meta.put(GitHandler.META_BRANCH, "main");
        if (checkoutCommit != null) {
            meta.put(GitHandler.META_CHECKOUT_COMMIT, checkoutCommit);
        }
        return meta;
    }

    private static int commitCountOf(Path repoDir) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            int count = 0;
            for (RevCommit ignored : git.log().call()) {
                count++;
            }
            return count;
        }
    }

    private RootDirHandle handle(String dirName, Map<String, Object> meta) throws Exception {
        Path rootDir = tempDir.resolve(dirName);
        Files.createDirectories(rootDir);
        WorkspaceDescriptor descriptor = WorkspaceDescriptor.builder()
                .tenant("test-tenant")
                .projectId("test-project")
                .dirName(dirName)
                .type(GitHandler.TYPE)
                .creatorProcessId("p-1")
                .createdAt("2026-05-12T10:00:00Z")
                .deleteOnCreatorClose(false)
                .metadata(meta)
                .build();
        return RootDirHandle.builder()
                .tenantId("test-tenant")
                .projectId("test-project")
                .dirName(dirName)
                .type(GitHandler.TYPE)
                .path(rootDir)
                .descriptor(descriptor)
                .build();
    }

    private static String workingTreeContent(RootDirHandle handle) throws IOException {
        return Files.readString(handle.getPath().resolve("note.txt"), StandardCharsets.UTF_8);
    }
}
