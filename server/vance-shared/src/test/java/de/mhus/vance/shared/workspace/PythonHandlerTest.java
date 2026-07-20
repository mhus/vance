package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonHandlerTest {

    private GitHandler gitHandler;
    private PythonHandler handler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        gitHandler = mock(GitHandler.class);
        handler = new PythonHandler(gitHandler);
    }

    @Test
    void type_returnsPython() {
        assertThat(handler.type()).isEqualTo("python");
    }

    @Test
    void init_withoutRepoUrl_initialisesGitRepoAndVenvAndGitignore() throws IOException {
        assumePython3Available();
        Path rootDir = createRootDirFolder("pyenv");
        WorkspaceDescriptor descriptor = descriptor("pyenv", null);
        RootDirHandle handle = handle(rootDir, descriptor);

        handler.init(handle, spec("pyenv"));

        verifyNoInteractions(gitHandler);
        assertThat(rootDir.resolve(".git")).isDirectory();
        assertThat(rootDir.resolve(".venv")).isDirectory();
        assertThat(rootDir.resolve(".venv/bin/python")).exists();
        assertThat(Files.readString(rootDir.resolve(".gitignore")))
                .contains(".venv/")
                .contains("__pycache__/");
        assertThat(handle.getDescriptor().getMetadata())
                .containsEntry(PythonHandler.META_PYTHON_PATH, "python3");
    }

    @Test
    void init_withRepoUrl_delegatesCloneToGitHandlerThenBuildsVenv() {
        assumePython3Available();
        Path rootDir = createRootDirFolder("repo");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GitHandler.META_REPO_URL, "https://example.invalid/repo.git");
        WorkspaceDescriptor descriptor = descriptor("repo", meta);
        RootDirHandle handle = handle(rootDir, descriptor);

        handler.init(handle, spec("repo"));

        verify(gitHandler, times(1)).init(any(RootDirHandle.class), any(RootDirSpec.class));
        // PythonHandler must run venv even when GitHandler was mocked
        // (the real handler would have populated the folder; the mock didn't).
        assertThat(rootDir.resolve(".venv")).isDirectory();
        assertThat(rootDir.resolve(".gitignore")).exists();
        assertThat(handle.getDescriptor().getMetadata())
                .containsEntry(PythonHandler.META_PYTHON_PATH, "python3");
    }

    @Test
    void init_withExplicitPythonPath_storesItInDescriptor() {
        assumePython3Available();
        Path rootDir = createRootDirFolder("custom");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(PythonHandler.META_PYTHON_PATH, "python3");
        WorkspaceDescriptor descriptor = descriptor("custom", meta);
        RootDirHandle handle = handle(rootDir, descriptor);

        handler.init(handle, spec("custom"));

        assertThat(handle.getDescriptor().getMetadata())
                .containsEntry(PythonHandler.META_PYTHON_PATH, "python3");
    }

    @Test
    void init_withMissingInterpreter_throwsWorkspaceException() {
        Path rootDir = createRootDirFolder("nopy");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(PythonHandler.META_PYTHON_PATH, "/definitely/not/a/python/binary");
        WorkspaceDescriptor descriptor = descriptor("nopy", meta);
        RootDirHandle handle = handle(rootDir, descriptor);

        assertThatThrownBy(() -> handler.init(handle, spec("nopy")))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("venv build");
    }

    @Test
    void suspend_withoutRemote_throwsWithGuidance() {
        Path rootDir = createRootDirFolder("local");
        WorkspaceDescriptor descriptor = descriptor("local", null);
        RootDirHandle handle = handle(rootDir, descriptor);

        assertThatThrownBy(() -> handler.suspend(handle))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("requires a configured git remote")
                .hasMessageContaining(GitHandler.META_REPO_URL);
        verify(gitHandler, never()).suspend(any());
    }

    @Test
    void suspend_withRemote_delegatesToGitHandler() {
        Path rootDir = createRootDirFolder("withRemote");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GitHandler.META_REPO_URL, "https://example.invalid/repo.git");
        WorkspaceDescriptor descriptor = descriptor("withRemote", meta);
        RootDirHandle handle = handle(rootDir, descriptor);

        handler.suspend(handle);

        verify(gitHandler, times(1)).suspend(handle);
    }

    @Test
    void recover_delegatesToGitHandlerThenRebuildsVenv() {
        assumePython3Available();
        Path rootDir = createRootDirFolder("recovered");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GitHandler.META_REPO_URL, "https://example.invalid/repo.git");
        meta.put(PythonHandler.META_PYTHON_PATH, "python3");
        WorkspaceDescriptor descriptor = descriptor("recovered", meta);
        RootDirHandle handle = handle(rootDir, descriptor);

        handler.recover(handle, descriptor);

        verify(gitHandler, times(1)).recover(handle, descriptor);
        assertThat(rootDir.resolve(".venv/bin/python")).exists();
        assertThat(handle.getDescriptor().getMetadata())
                .containsEntry(PythonHandler.META_PYTHON_PATH, "python3");
    }

    @Test
    void rebuildVenv_recreatesVenvAndUpdatesDescriptor() throws IOException {
        assumePython3Available();
        Path rootDir = createRootDirFolder("rebuild");
        WorkspaceDescriptor descriptor = descriptor("rebuild", null);
        RootDirHandle handle = handle(rootDir, descriptor);
        handler.init(handle, spec("rebuild"));

        // Touch a marker inside the existing .venv to prove it's wiped.
        Path marker = rootDir.resolve(".venv/__marker__");
        Files.writeString(marker, "before", StandardCharsets.UTF_8);
        assertThat(marker).exists();

        handler.rebuildVenv(handle, "python3");

        assertThat(marker).doesNotExist();
        assertThat(rootDir.resolve(".venv/bin/python")).exists();
        assertThat(handle.getDescriptor().getMetadata())
                .containsEntry(PythonHandler.META_PYTHON_PATH, "python3");
    }

    @Test
    void init_withRequirementsFile_autoInstallsAfterClone() throws IOException {
        assumePython3Available();
        Path rootDir = createRootDirFolder("autoinstall");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GitHandler.META_REPO_URL, "https://example.invalid/repo.git");
        WorkspaceDescriptor descriptor = descriptor("autoinstall", meta);
        RootDirHandle handle = handle(rootDir, descriptor);
        // Simulate the clone result: a cloned repo carries a
        // requirements.txt. The mocked GitHandler.init is a no-op, so
        // we drop the file ourselves before init runs.
        Files.writeString(rootDir.resolve(PythonHandler.REQUIREMENTS_FILE),
                "pip\n", StandardCharsets.UTF_8);

        handler.init(handle, spec("autoinstall"));

        // Auto-install ran without error → venv + pip-installed package present.
        assertThat(rootDir.resolve(".venv/bin/python")).exists();
        assertThat(rootDir.resolve(PythonHandler.REQUIREMENTS_FILE)).exists();
    }

    @Test
    void rebuildVenv_withRequirementsFile_runsPipInstall() throws IOException {
        assumePython3Available();
        Path rootDir = createRootDirFolder("withreqs");
        WorkspaceDescriptor descriptor = descriptor("withreqs", null);
        RootDirHandle handle = handle(rootDir, descriptor);
        handler.init(handle, spec("withreqs"));

        // pip is a trivial dep that's already in the venv — listing it here
        // forces pip-install to run but does not need network access.
        Files.writeString(rootDir.resolve(PythonHandler.REQUIREMENTS_FILE),
                "pip\n", StandardCharsets.UTF_8);

        handler.rebuildVenv(handle, "python3");

        assertThat(rootDir.resolve(".venv/bin/python")).exists();
    }

    @Test
    void init_withPackagesOption_pipInstallsThem() {
        assumePython3Available();
        Path rootDir = createRootDirFolder("pkgs");
        Map<String, Object> meta = new LinkedHashMap<>();
        // pip is already inside the fresh venv — installs without network.
        meta.put(PythonHandler.META_PACKAGES, java.util.List.of("pip"));
        WorkspaceDescriptor descriptor = descriptor("pkgs", meta);
        RootDirHandle handle = handle(rootDir, descriptor);

        handler.init(handle, spec("pkgs"));

        assertThat(rootDir.resolve(".venv/bin/python")).exists();
    }

    @Test
    void init_withPackageSpecStartingWithDash_throwsOptionInjection() {
        assumePython3Available();
        Path rootDir = createRootDirFolder("inject");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(PythonHandler.META_PACKAGES, java.util.List.of("--index-url=http://evil"));
        WorkspaceDescriptor descriptor = descriptor("inject", meta);
        RootDirHandle handle = handle(rootDir, descriptor);

        assertThatThrownBy(() -> handler.init(handle, spec("inject")))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("option injection");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void assumePython3Available() {
        assumeTrue(commandWorks("python3", "--version"), "python3 not on PATH");
    }

    private static boolean commandWorks(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private Path createRootDirFolder(String name) {
        Path rootDir = tempDir.resolve(name);
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return rootDir;
    }

    private static WorkspaceDescriptor descriptor(String dirName, Map<String, Object> meta) {
        return WorkspaceDescriptor.builder()
                .tenant("test-tenant")
                .projectId("test-project")
                .dirName(dirName)
                .type(PythonHandler.TYPE)
                .creatorProcessId("p-1")
                .createdAt("2026-05-12T10:00:00Z")
                .deleteOnCreatorClose(false)
                .metadata(meta)
                .build();
    }

    private static RootDirHandle handle(Path rootDir, WorkspaceDescriptor descriptor) {
        return RootDirHandle.builder()
                .tenantId("test-tenant")
                .projectId("test-project")
                .dirName(descriptor.getDirName())
                .type(PythonHandler.TYPE)
                .path(rootDir)
                .descriptor(descriptor)
                .build();
    }

    private static RootDirSpec spec(String labelHint) {
        return RootDirSpec.builder()
                .tenantId("test-tenant")
                .projectId("test-project")
                .type(PythonHandler.TYPE)
                .creatorProcessId("p-1")
                .labelHint(labelHint)
                .deleteOnCreatorClose(false)
                .build();
    }
}
