package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.runner.RunnerBindingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace lives on the disk of the pod that ran the project. Disposing or
 * deleting on a machine that never held the folder is a quiet no-op, and a
 * delete says whether there was a folder to remove.
 */
class WorkspaceServiceDisposeTest {

    private static WorkspaceService service(Path root) {
        WorkspaceProperties properties = mock(WorkspaceProperties.class);
        when(properties.getRoot()).thenReturn(root.toString());
        return new WorkspaceService(
                properties, List.of(), mock(WorkspaceRootService.class), mock(RunnerBindingService.class));
    }

    @Test
    void dispose_withoutLocalFolder_doesNothing(@TempDir Path tmp) {
        WorkspaceService service = service(tmp);

        service.dispose("acme", "kunde-x");

        assertThat(service.existsForProject("acme", "kunde-x")).isFalse();
    }

    @Test
    void deleteForProject_reportsWhetherAFolderWasThere(@TempDir Path tmp) throws Exception {
        WorkspaceService service = service(tmp);
        assertThat(service.deleteForProject("acme", "kunde-x")).isFalse();

        Files.createDirectories(tmp.resolve("acme").resolve("kunde-x"));

        assertThat(service.deleteForProject("acme", "kunde-x")).isTrue();
        assertThat(tmp.resolve("acme").resolve("kunde-x")).doesNotExist();
    }
}
