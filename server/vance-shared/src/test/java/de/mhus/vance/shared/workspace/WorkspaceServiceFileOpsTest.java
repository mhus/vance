package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import de.mhus.vance.exec.RootDirException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file operations of {@link WorkspaceService} run in {@code vance-exec}
 * ({@code RootDirFiles}); this pins what the facade adds: the brain's exception
 * types, with the plain-Java exception as cause.
 */
class WorkspaceServiceFileOpsTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "kunde-x";
    private static final String DIR = "ws";

    private WorkspaceService serviceResolving(String relativePath, Path file) {
        WorkspaceService service = spy(new WorkspaceService(
                mock(WorkspaceProperties.class),
                List.of(),
                mock(WorkspaceSnapshotRepository.class),
                mock(WorkspaceRootService.class)));
        doReturn(file).when(service).resolve(TENANT, PROJECT, DIR, relativePath);
        return service;
    }

    @Test
    void readBytes_overTheLimit_isTheBrainsSizeException(@TempDir Path tmp) throws Exception {
        Path file = Files.write(tmp.resolve("big.bin"), new byte[100]);
        WorkspaceService service = serviceResolving("big.bin", file);

        assertThatThrownBy(() -> service.readBytes(TENANT, PROJECT, DIR, "big.bin", 10))
                .isInstanceOfSatisfying(WorkspaceFileSizeExceededException.class, e -> {
                    assertThat(e.getFileSize()).isEqualTo(100);
                    assertThat(e.getLimit()).isEqualTo(10);
                    assertThat(e.getCause()).isInstanceOf(RootDirException.class);
                });
    }

    @Test
    void read_missingFile_isAWorkspaceExceptionWithTheSameMessage(@TempDir Path tmp) {
        WorkspaceService service = serviceResolving("nope.txt", tmp.resolve("nope.txt"));

        assertThatThrownBy(() -> service.read(TENANT, PROJECT, DIR, "nope.txt", 0))
                .isInstanceOf(WorkspaceException.class)
                .hasMessage("Not found: nope.txt");
    }

    @Test
    void delete_directory_isRefused(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("d"));
        WorkspaceService service = serviceResolving("d", dir);

        assertThatThrownBy(() -> service.delete(TENANT, PROJECT, DIR, "d"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessage("Refusing to delete a directory: d");
    }

    @Test
    void readablePath_returnsTheResolvedFile(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("a.js"), "1");
        WorkspaceService service = serviceResolving("a.js", file);

        assertThat(service.readablePath(TENANT, PROJECT, DIR, "a.js")).isEqualTo(file);
    }
}
