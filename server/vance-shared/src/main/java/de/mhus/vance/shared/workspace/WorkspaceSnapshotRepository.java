package de.mhus.vance.shared.workspace;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Package-private — only {@link WorkspaceService} reads/writes snapshots. */
interface WorkspaceSnapshotRepository extends MongoRepository<WorkspaceSnapshotDocument, String> {

    List<WorkspaceSnapshotDocument> findByProjectId(String projectId);

    Optional<WorkspaceSnapshotDocument> findByProjectIdAndDirName(String projectId, String dirName);

    long deleteByProjectIdAndDirName(String projectId, String dirName);

    long deleteByProjectId(String projectId);

    // Tenant-scoped variants. A project name is unique inside a tenant and
    // nowhere else, so the project-only methods above match rows of a
    // same-named project in another tenant. The maintenance paths use these;
    // the older call sites are a defect of their own and are not touched here.

    List<WorkspaceSnapshotDocument> findByTenantAndProjectId(String tenant, String projectId);

    long deleteByTenantAndProjectId(String tenant, String projectId);
}
