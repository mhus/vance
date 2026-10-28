package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.ProcessSpec;
import de.mhus.vance.exec.RootDirHandle;
import de.mhus.vance.exec.WorkspaceDescriptor;
import de.mhus.vance.shared.process.TestProcessLaunchers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/**
 * npm command lines and the size cap of {@link NodeHandler}. A shell script stands
 * in for npm and records every argv, so neither npm nor network is needed.
 */
class NodeHandlerTest {

    @TempDir
    Path tmp;

    private Path rootDir;
    private Path argLog;
    private WorkspaceProperties properties;
    private NodeHandler handler;
    private RootDirHandle handle;

    @BeforeEach
    void setUp() throws IOException {
        rootDir = Files.createDirectories(tmp.resolve("_jsengine"));
        argLog = tmp.resolve("npm-calls.log");
        Path fakeNpm = tmp.resolve("fake-npm.sh");
        Files.writeString(fakeNpm, "#!/bin/sh\necho \"$@\" >> '" + argLog + "'\n");
        Files.setPosixFilePermissions(fakeNpm, PosixFilePermissions.fromString("rwx------"));

        properties = new WorkspaceProperties();
        properties.getNode().setNpmPath(fakeNpm.toString());
        properties.getNode().setRegistry("https://registry.example.test/");
        handler = new NodeHandler(
                mock(GitHandler.class), properties, TestProcessLaunchers.provider(TestProcessLaunchers.direct()));

        WorkspaceDescriptor descriptor = new WorkspaceDescriptor();
        descriptor.setMetadata(Map.of("npmPath", "/tmp/attacker-binary"));
        handle = RootDirHandle.builder()
                .tenantId("acme")
                .projectId("proj-1")
                .dirName("_jsengine")
                .type(NodeHandler.TYPE)
                .path(rootDir)
                .descriptor(descriptor)
                .build();
    }

    @Test
    void install_passesSpecAfterDoubleDashWithPinnedRegistry() throws IOException {
        handler.install(handle, "lodash@^4");

        assertThat(calls())
                .containsExactly("install --save --ignore-scripts --no-audit --no-fund"
                        + " --registry=https://registry.example.test/ -- lodash@^4");
    }

    @Test
    void install_usesConfiguredNpmNotDescriptorMetadata() throws IOException {
        handler.install(handle, "lodash");

        // The fake npm (from properties) recorded the call; the metadata path was never run.
        assertThat(calls()).hasSize(1);
    }

    @Test
    void install_fileSpec_isRejectedBeforeNpmRuns() {
        assertThatThrownBy(() -> handler.install(handle, "file:/etc")).isInstanceOf(WorkspaceException.class);

        assertThat(Files.exists(argLog)).isFalse();
    }

    @Test
    void install_pastSizeLimit_isRolledBack() throws IOException {
        properties.getNode().setMaxBytes(1_000);
        Path pkg = Files.createDirectories(rootDir.resolve("node_modules").resolve("big"));
        Files.write(pkg.resolve("blob.bin"), new byte[2_000]);

        assertThatThrownBy(() -> handler.install(handle, "big@1.0.0"))
                .isInstanceOf(WorkspaceQuotaExceededException.class);

        assertThat(calls()).last().isEqualTo("uninstall --save --no-audit --no-fund -- big");
    }

    @Test
    void uninstall_withVersion_isRejected() {
        assertThatThrownBy(() -> handler.uninstall(handle, "lodash@4")).isInstanceOf(WorkspaceException.class);
    }

    @Test
    void install_runsThroughTheLauncherWithTheProjectScope() throws IOException {
        List<ProcessSpec> started = new ArrayList<>();
        ProcessLauncher direct = TestProcessLaunchers.direct();
        NodeHandler recording =
                new NodeHandler(mock(GitHandler.class), properties, TestProcessLaunchers.provider(spec -> {
                    started.add(spec);
                    return direct.start(spec);
                }));

        recording.install(handle, "lodash");

        assertThat(started).hasSize(1);
        assertThat(started.get(0).scope()).isEqualTo(new ProcessSpec.Scope("acme", "proj-1", null));
        assertThat(started.get(0).workingDir()).isEqualTo(rootDir);
    }

    @Test
    void install_withoutLauncher_failsInsteadOfStartingUnsealed() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProcessLauncher> none = mock(ObjectProvider.class);
        NodeHandler shell = new NodeHandler(mock(GitHandler.class), properties, none);

        assertThatThrownBy(() -> shell.install(handle, "lodash"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("no ProcessLauncher");
    }

    @Test
    void suspend_withoutGitRemote_keepsNothingAndDoesNotBlock() {
        GitHandler git = mock(GitHandler.class);
        NodeHandler withGit =
                new NodeHandler(git, properties, TestProcessLaunchers.provider(TestProcessLaunchers.direct()));

        withGit.suspend(handle);

        org.mockito.Mockito.verifyNoInteractions(git);
    }

    @Test
    void recover_withoutGitRemote_rebuildsAndReinstallsDeclaredPackages() throws IOException {
        WorkspaceDescriptor descriptor = new WorkspaceDescriptor();
        descriptor.setMetadata(Map.of(NodeHandler.META_PACKAGES, List.of("lodash@^4")));

        handler.recover(handle, descriptor);

        assertThat(calls())
                .containsExactly(
                        "init -y",
                        "install --save --ignore-scripts --no-audit --no-fund"
                                + " --registry=https://registry.example.test/ -- lodash@^4");
        assertThat(rootDir.resolve(".git")).isDirectory();
    }

    private List<String> calls() throws IOException {
        return Files.readAllLines(argLog);
    }
}
