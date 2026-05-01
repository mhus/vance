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
}
