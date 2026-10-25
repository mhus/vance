package de.mhus.vance.shared.workspace;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Package-private — only {@link WorkspaceService} reads/writes snapshots. */
interface WorkspaceSnapshotRepository extends MongoRepository<WorkspaceSnapshotDocument, String> {

    // Always tenant-scoped: a project name is unique inside a tenant and
    // nowhere else, so a project-only lookup would match the snapshots of a
    // same-named project in another tenant — recover them into the wrong
    // workspace, overwrite them on suspend, delete them on close.

    List<WorkspaceSnapshotDocument> findByTenantAndProjectId(String tenant, String projectId);

    Optional<WorkspaceSnapshotDocument> findByTenantAndProjectIdAndDirName(
            String tenant, String projectId, String dirName);

    long deleteByTenantAndProjectId(String tenant, String projectId);
}
