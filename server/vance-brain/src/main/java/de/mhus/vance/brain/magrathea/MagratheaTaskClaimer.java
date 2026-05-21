package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Pod-local scheduled scanner. Every {@value #SCAN_INTERVAL_MS}ms it
 * scans {@code magrathea_tasks} for {@link MagratheaTaskStatus#PENDING} rows
 * whose {@code nextAttemptAt} is due, atomically flips them to
 * {@link MagratheaTaskStatus#CLAIMED} via Mongo {@code findAndModify}, and
 * dispatches each claimed task to the matching {@link MagratheaProjectLane}
 * for execution (plan §6.2).
 *
 * <p>Optimistic version is enforced via the {@code @Version} field of
 * {@link MagratheaTaskDocument}: the CAS update predicates on the current
 * version, so two pods racing on the same row produce exactly one
 * winner.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaTaskClaimer {

    private static final long SCAN_INTERVAL_MS = 2_000L;
    private static final int CLAIM_BATCH = 32;

    private final MongoTemplate mongoTemplate;
    private final MagratheaTaskService taskService;
    private final MagratheaProjectLaneManager laneManager;
    private final MagratheaTaskExecutor taskExecutor;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = SCAN_INTERVAL_MS)
    public void scan() {
        Instant now = Instant.now();
        Query query = new Query(
                Criteria.where("status").is(MagratheaTaskStatus.PENDING)
                        .and("nextAttemptAt").lte(now))
                .with(org.springframework.data.domain.Sort.by("nextAttemptAt").ascending())
                .limit(CLAIM_BATCH);
        List<MagratheaTaskDocument> tasks = mongoTemplate.find(query, MagratheaTaskDocument.class);
        if (tasks.isEmpty()) return;

        String podId = podId();
        Instant claimedAt = Instant.now();
        for (MagratheaTaskDocument task : tasks) {
            Optional<MagratheaTaskDocument> claimed = taskService.claim(task.getId(), podId, claimedAt);
            if (claimed.isEmpty()) {
                log.debug("Magrathea task {} claim lost to another pod", task.getId());
                continue;
            }
            MagratheaTaskDocument claimedTask = claimed.get();
            laneManager.submit(claimedTask.getProjectId(),
                    () -> taskExecutor.execute(claimedTask));
        }
    }

    private static String podId() {
        String envPod = System.getenv("POD_NAME");
        if (StringUtils.hasText(envPod)) return envPod;
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "unknown-pod";
        }
    }
}
