package de.mhus.vance.shared.eventlog;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link EventLogDocument}. Package-private —
 * callers go through {@link EventLogService}.
 */
interface EventLogRepository extends MongoRepository<EventLogDocument, String> {

    List<EventLogDocument> findByCorrelationId(String correlationId);
}
