package de.mhus.vance.shared.workspace;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceService#dispose} is terminal: the snapshots in Mongo go
 * even on a pod that never held the folder — an archive landing off the home
 * pod disposes there, and a surviving snapshot would come back on unarchive.
 */
class WorkspaceServiceDisposeTest {

    @Test
    void dispose_withoutLocalFolder_stillDeletesTheSnapshots(@TempDir Path tmp) {
        WorkspaceProperties properties = mock(WorkspaceProperties.class);
        when(properties.getRoot()).thenReturn(tmp.toString());
        WorkspaceSnapshotRepository snapshots = mock(WorkspaceSnapshotRepository.class);
        WorkspaceService service =
                new WorkspaceService(properties, List.of(), snapshots, mock(WorkspaceRootService.class));

        service.dispose("acme", "kunde-x");

        verify(snapshots).deleteByTenantAndProjectId("acme", "kunde-x");
    }
}
