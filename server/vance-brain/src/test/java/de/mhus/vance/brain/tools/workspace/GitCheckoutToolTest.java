package de.mhus.vance.brain.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.workspace.GitHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Metadata wiring of {@link GitCheckoutTool}: what the LLM passes must
 * land in the GitHandler metadata (the handler is the one acting on
 * it), and the tool's answer must name dirName, path and the resulting
 * commit — the fields the {@code vance-sources} manual keys on.
 */
class GitCheckoutToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("tenant", "project", "session", "process", "user");

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final GitCheckoutTool tool = new GitCheckoutTool(workspaceService);

    @Test
    void invoke_withCommit_wiresCheckoutCommitIntoHandlerMetadata() {
        when(workspaceService.createRootDir(any())).thenReturn(handle("cloned", "abc123"));

        Map<String, Object> out = tool.invoke(
                Map.of(
                        "repoUrl", "https://github.com/mhus/vance.git",
                        "commit", "abc123",
                        "asWorkingDir", true),
                CTX);

        ArgumentCaptor<RootDirSpec> captor = ArgumentCaptor.forClass(RootDirSpec.class);
        verify(workspaceService).createRootDir(captor.capture());
        Map<String, Object> meta = captor.getValue().getMetadata();
        assertThat(meta)
                .containsEntry(GitHandler.META_REPO_URL, "https://github.com/mhus/vance.git")
                .containsEntry(GitHandler.META_CHECKOUT_COMMIT, "abc123");

        verify(workspaceService).setWorkingDir(eq("tenant"), eq("project"), eq("process"), eq("cloned"));

        assertThat(out)
                .containsEntry("dirName", "cloned")
                .containsEntry("requestedCommit", "abc123")
                .containsEntry("commit", "abc123")
                .containsEntry("workingDir", true);
    }

    @Test
    void invoke_withoutCommit_leavesCheckoutCommitUnset() {
        when(workspaceService.createRootDir(any())).thenReturn(handle("plain", "tip456"));

        Map<String, Object> out = tool.invoke(
                Map.of(
                        "repoUrl", "https://example.com/repo.git",
                        "branch", "main"),
                CTX);

        ArgumentCaptor<RootDirSpec> captor = ArgumentCaptor.forClass(RootDirSpec.class);
        verify(workspaceService).createRootDir(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .containsEntry(GitHandler.META_REPO_URL, "https://example.com/repo.git")
                .containsEntry(GitHandler.META_BRANCH, "main")
                .doesNotContainKey(GitHandler.META_CHECKOUT_COMMIT);

        // setWorkingDir must not have been called — no asWorkingDir param.
        org.mockito.Mockito.verify(workspaceService, org.mockito.Mockito.never())
                .setWorkingDir(any(), any(), any(), any());

        assertThat(out).containsEntry("commit", "tip456").containsEntry("workingDir", false);
    }

    @Test
    void invoke_withDepth_wiresDepthIntoHandlerMetadata() {
        when(workspaceService.createRootDir(any())).thenReturn(handle("shallow", "tip456"));

        Map<String, Object> out = tool.invoke(
                Map.of(
                        "repoUrl", "https://github.com/mhus/vance.git",
                        "branch", "main",
                        "depth", 1),
                CTX);

        ArgumentCaptor<RootDirSpec> captor = ArgumentCaptor.forClass(RootDirSpec.class);
        verify(workspaceService).createRootDir(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .containsEntry(GitHandler.META_REPO_URL, "https://github.com/mhus/vance.git")
                .containsEntry(GitHandler.META_DEPTH, 1);
        assertThat(out).containsEntry("dirName", "shallow");
    }

    @Test
    void invoke_withNonPositiveDepth_leavesDepthUnset() {
        when(workspaceService.createRootDir(any())).thenReturn(handle("full", "tip456"));

        tool.invoke(Map.of("repoUrl", "https://example.com/r.git", "depth", 0), CTX);

        ArgumentCaptor<RootDirSpec> captor = ArgumentCaptor.forClass(RootDirSpec.class);
        verify(workspaceService).createRootDir(captor.capture());
        assertThat(captor.getValue().getMetadata()).doesNotContainKey(GitHandler.META_DEPTH);
    }

    @Test
    void invoke_withoutRepoUrl_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of("branch", "main"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("repoUrl");
    }

    @Test
    void tool_isDeferredWorkspaceGitTool() {
        assertThat(tool.name()).isEqualTo("git_checkout");
        assertThat(tool.primary()).isFalse();
        assertThat(tool.deferred()).isTrue();
        assertThat(tool.labels()).containsExactlyInAnyOrder("write", "side-effect");
    }

    // ──────────────────── helpers ────────────────────

    private static RootDirHandle handle(String dirName, String commit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GitHandler.META_COMMIT, commit);
        WorkspaceDescriptor descriptor = WorkspaceDescriptor.builder()
                .tenant("tenant")
                .projectId("project")
                .dirName(dirName)
                .type(GitHandler.TYPE)
                .creatorProcessId("process")
                .createdAt("2026-09-09T10:00:00Z")
                .deleteOnCreatorClose(false)
                .metadata(meta)
                .build();
        return RootDirHandle.builder()
                .tenantId("tenant")
                .projectId("project")
                .dirName(dirName)
                .type(GitHandler.TYPE)
                .path(Path.of("/tmp/" + dirName))
                .descriptor(descriptor)
                .build();
    }
}
