package de.mhus.vance.shared.magrathea;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Owns the data plane for {@code magrathea_timers}. Used by the
 * {@code TimerTaskExecutor} (insert), the {@code GateTaskExecutor}
 * (insert for inbox timeouts) and the {@code MagratheaTimerScanner}
 * (scan + atomic claim).
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaTimerService {

    private final MagratheaTimerRepository repository;
    private final MongoTemplate mongoTemplate;

    public MagratheaTimerDocument insert(MagratheaTimerDocument timer) {
        if (timer.getCreatedAt() == null) {
            timer.setCreatedAt(Instant.now());
        }
        return repository.save(timer);
    }

    public Optional<MagratheaTimerDocument> findByLinkedTaskId(String linkedTaskId) {
        return repository.findByLinkedTaskId(linkedTaskId);
    }

    public List<MagratheaTimerDocument> findPending(Instant cutoff, int limit) {
        return repository.findByFiredAtIsNullAndFireAtLessThanEqual(
                cutoff, PageRequest.of(0, limit));
    }

    /**
     * Atomic claim: marks {@link MagratheaTimerDocument#getFiredAt()}
     * non-null. Returns the fresh document when this caller won the
     * race, {@link Optional#empty()} when another pod beat us to it.
     */
    public Optional<MagratheaTimerDocument> claimFire(String timerId, Instant firedAt) {
        MagratheaTimerDocument current = repository.findById(timerId).orElse(null);
        if (current == null || current.getFiredAt() != null) return Optional.empty();

        Query q = new Query(Criteria.where("_id").is(timerId)
                .and("firedAt").is(null)
                .and("version").is(current.getVersion()));
        Update u = new Update().set("firedAt", firedAt).inc("version", 1L);
        UpdateResult res = mongoTemplate.updateFirst(q, u, MagratheaTimerDocument.class);
        if (res.getModifiedCount() == 0) return Optional.empty();
        return repository.findById(timerId);
    }

    public long deleteRun(String workflowRunId) {
        return repository.deleteByWorkflowRunId(workflowRunId);
    }
}
