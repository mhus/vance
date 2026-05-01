package de.mhus.vance.shared.workspace;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted snapshot of a single suspended RootDir. Written by
 * {@link WorkspaceService#suspendAll}, read by
 * {@link WorkspaceService#recoverAll}, deleted on successful recover.
 *
 * <p>Datenhoheit: only {@link WorkspaceService} reads or writes this
 * collection. Other services use the public service API.
 */
@Document(collection = "workspace_snapshots")
@CompoundIndexes({
        @CompoundIndex(
                name = "project_dirname_idx",
                def = "{ 'projectId': 1, 'dirName': 1 }",
                unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceSnapshotDocument {

    @Id
    private @Nullable String id;

    private String tenant = "";

    private String projectId = "";

    private String dirName = "";

    /** Full descriptor at the time of suspend, including handler-set recovery hints. */
    private @Nullable WorkspaceDescriptor descriptor;

    private @Nullable Instant suspendedAt;
}
