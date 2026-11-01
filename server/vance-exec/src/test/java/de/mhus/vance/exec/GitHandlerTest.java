package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The handler's own logic — metadata keys, depth rule, suspend branch, messages —
 * against a recording {@link GitOperations}. The real-repository behaviour is
 * covered where the JGit implementation lives (vance-shared).
 */
class GitHandlerTest {

    @TempDir
    Path tmp;

    private final List<String> calls = new ArrayList<>();
    private boolean dirty;
    private @Nullable String failOn;
    private GitHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GitHandler(new GitOperations() {
            @Override
            public void init(Path dir) {
                calls.add("init");
            }

            @Override
            public void clone(String repoUrl, Path dir, String branch, @Nullable Integer depth, Scope scope) {
                calls.add("clone " + repoUrl + " branch=" + branch + " depth=" + depth + " public="
                        + scope.publicAccess() + " tenant=" + scope.tenantId());
                maybeFail("clone");
            }

            @Override
            public void checkout(Path repo, String ref) {
                calls.add("checkout " + ref);
                maybeFail("checkout");
            }

            @Override
            public @Nullable String head(Path repo) {
                return "abc123";
            }

            @Override
            public boolean isClean(Path repo) {
                return !dirty;
            }

            @Override
            public void checkoutNewBranch(Path repo, String branch) {
                calls.add("branch " + branch);
            }

            @Override
            public void commitAll(Path repo, String message) {
                calls.add("commit " + message);
            }

            @Override
            public void push(Path repo, String remote, String branch, Scope scope) {
                calls.add("push " + remote + " " + branch + " public=" + scope.publicAccess());
                maybeFail("push");
            }
        });
    }

    private void maybeFail(String op) {
        if (op.equals(failOn)) {
            throw new GitOperationException(op + " exploded");
        }
    }

    private RootDirHandle handle(Map<String, Object> meta) {
        WorkspaceDescriptor d = WorkspaceDescriptor.builder()
                .tenant("acme")
                .projectId("proj-1")
                .dirName("repo")
                .type("git")
                .metadata(meta)
                .build();
        return RootDirHandle.builder()
                .tenantId("acme")
                .projectId("proj-1")
                .dirName("repo")
                .type("git")
                .path(tmp)
                .descriptor(d)
                .build();
    }

    private static Map<String, Object> meta(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void init_clonesTheBranch_defaultsToMain_andRecordsHead() {
        RootDirHandle handle = handle(meta(GitHandler.META_REPO_URL, "https://x.test/r.git"));

        handler.init(handle, null);

        assertThat(calls).containsExactly("clone https://x.test/r.git branch=main depth=null public=false tenant=acme");
        assertThat(handle.getDescriptor().getMetadata()).containsEntry(GitHandler.META_COMMIT, "abc123");
    }

    @Test
    void init_passesBranchPublicFlagAndOnlyAPositiveDepth() {
        for (Object depth : new Object[] {3, "5", " 7 "}) {
            calls.clear();
            handler.init(
                    handle(meta(
                            GitHandler.META_REPO_URL,
                            "u",
                            GitHandler.META_BRANCH,
                            "dev",
                            GitHandler.META_PUBLIC,
                            true,
                            GitHandler.META_DEPTH,
                            depth)),
                    null);
            assertThat(calls).hasSize(1);
            assertThat(calls.get(0)).contains("branch=dev").contains("public=true");
            assertThat(calls.get(0)).contains("depth=" + depth.toString().trim());
        }
        for (Object depth : new Object[] {0, -2, "", "soon", null}) {
            calls.clear();
            handler.init(handle(meta(GitHandler.META_REPO_URL, "u", GitHandler.META_DEPTH, depth)), null);
            assertThat(calls.get(0)).as("depth %s means a full clone", depth).contains("depth=null");
        }
    }

    @Test
    void init_checkoutCommit_detachesAfterTheClone() {
        handler.init(handle(meta(GitHandler.META_REPO_URL, "u", GitHandler.META_CHECKOUT_COMMIT, "deadbeef")), null);

        assertThat(calls).extracting(c -> c.split(" ")[0]).containsExactly("clone", "checkout");
        assertThat(calls.get(1)).isEqualTo("checkout deadbeef");
    }

    @Test
    void init_failedCheckout_failsTheWholeInitWithBothMessages() {
        failOn = "checkout";

        assertThatThrownBy(() -> handler.init(
                        handle(meta(GitHandler.META_REPO_URL, "u", GitHandler.META_CHECKOUT_COMMIT, "nope")), null))
                .isInstanceOf(RootDirException.class)
                .hasMessage("git clone failed for u: checkout of commit 'nope' failed: checkout exploded");
    }

    @Test
    void init_withoutRepoUrl_isRefused() {
        assertThatThrownBy(() -> handler.init(handle(meta()), null))
                .isInstanceOf(RootDirException.class)
                .hasMessage("git metadata is missing required field 'repoUrl'");
    }

    @Test
    void init_cloneFailure_keepsTheBackendMessage() {
        failOn = "clone";

        assertThatThrownBy(() -> handler.init(handle(meta(GitHandler.META_REPO_URL, "u")), null))
                .hasMessage("git clone failed for u: clone exploded");
    }
}
