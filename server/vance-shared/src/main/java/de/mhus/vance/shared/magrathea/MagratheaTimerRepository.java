package de.mhus.vance.shared.magrathea;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link MagratheaTimerDocument}. Package-private —
 * callers go through {@code MagratheaTimerService} in
 * {@code vance-shared}.
 */
interface MagratheaTimerRepository extends MongoRepository<MagratheaTimerDocument, String> {

    /** Find pending timers ready to fire — used by the scanner. */
    List<MagratheaTimerDocument> findByFiredAtIsNullAndFireAtLessThanEqual(Instant cutoff, Pageable page);

    Optional<MagratheaTimerDocument> findByLinkedTaskId(String linkedTaskId);

    long deleteByWorkflowRunId(String workflowRunId);
}
