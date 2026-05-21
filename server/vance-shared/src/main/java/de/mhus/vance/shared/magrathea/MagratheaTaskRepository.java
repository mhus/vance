package de.mhus.vance.shared.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link MagratheaTaskDocument}. Package-private —
 * callers go through {@code MagratheaWorkflowService} / {@code MagratheaTaskClaimer}
 * in {@code vance-brain}. Atomic claim/release operations use
 * {@code MongoTemplate.findAndModify} (see CLAUDE.md — atomic ops
 * outside repository semantics).
 */
interface MagratheaTaskRepository extends MongoRepository<MagratheaTaskDocument, String> {

    List<MagratheaTaskDocument> findByProjectIdAndStatusAndNextAttemptAtBefore(
            String projectId, MagratheaTaskStatus status, Instant cutoff, Pageable page);

    List<MagratheaTaskDocument> findByStatusAndClaimedAtBefore(
            MagratheaTaskStatus status, Instant cutoff, Pageable page);

    List<MagratheaTaskDocument> findByWorkflowRunId(String workflowRunId);

    Optional<MagratheaTaskDocument> findByWorkflowRunIdAndStateNameAndStatusIn(
            String workflowRunId, String stateName, List<MagratheaTaskStatus> statuses);

    Optional<MagratheaTaskDocument> findBySubProcessId(String subProcessId);

    Optional<MagratheaTaskDocument> findBySubWorkflowRunId(String subWorkflowRunId);

    Optional<MagratheaTaskDocument> findByInboxItemId(String inboxItemId);

    long deleteByWorkflowRunId(String workflowRunId);
}
