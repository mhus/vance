package de.mhus.vance.shared.hactar;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link HactarTimerDocument}. Package-private —
 * callers go through {@code HactarTimerService} in
 * {@code vance-shared}.
 */
interface HactarTimerRepository extends MongoRepository<HactarTimerDocument, String> {

    /** Find pending timers ready to fire — used by the scanner. */
    List<HactarTimerDocument> findByFiredAtIsNullAndFireAtLessThanEqual(Instant cutoff, Pageable page);

    Optional<HactarTimerDocument> findByLinkedTaskId(String linkedTaskId);

    long deleteByWorkflowRunId(String workflowRunId);
}
