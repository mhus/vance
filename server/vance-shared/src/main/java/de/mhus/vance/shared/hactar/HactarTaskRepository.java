package de.mhus.vance.shared.hactar;

import de.mhus.vance.api.hactar.HactarTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link HactarTaskDocument}. Package-private —
 * callers go through {@code HactarWorkflowService} / {@code HactarTaskClaimer}
 * in {@code vance-brain}. Atomic claim/release operations use
 * {@code MongoTemplate.findAndModify} (see CLAUDE.md — atomic ops
 * outside repository semantics).
 */
interface HactarTaskRepository extends MongoRepository<HactarTaskDocument, String> {

    List<HactarTaskDocument> findByProjectIdAndStatusAndNextAttemptAtBefore(
            String projectId, HactarTaskStatus status, Instant cutoff, Pageable page);

    List<HactarTaskDocument> findByStatusAndClaimedAtBefore(
            HactarTaskStatus status, Instant cutoff, Pageable page);

    List<HactarTaskDocument> findByWorkflowRunId(String workflowRunId);

    Optional<HactarTaskDocument> findByWorkflowRunIdAndStateNameAndStatusIn(
            String workflowRunId, String stateName, List<HactarTaskStatus> statuses);

    Optional<HactarTaskDocument> findBySubProcessId(String subProcessId);

    Optional<HactarTaskDocument> findBySubWorkflowRunId(String subWorkflowRunId);

    Optional<HactarTaskDocument> findByInboxItemId(String inboxItemId);

    long deleteByWorkflowRunId(String workflowRunId);
}
