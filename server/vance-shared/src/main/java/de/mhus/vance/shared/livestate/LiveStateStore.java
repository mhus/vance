package de.mhus.vance.shared.livestate;

import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.api.projects.RunnerPhase;
import de.mhus.vance.api.projects.RunnerStatusDto;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that reads and writes the live state of projects and runners
 * in Redis. One key per project and one per runner, each with a TTL that its
 * writer keeps refreshing — a writer that dies simply stops refreshing, and the
 * state turns {@code UNKNOWN} on its own instead of showing an old value.
 *
 * <p>Brain writes the project entries (claim, takeover, release, lease renewal);
 * the cloud controller in Anus writes the runner entries. Readers never
 * write.
 *
 * <p>Information only: a failed write is logged at debug level and not reported to the caller.
 * The entry that is returned is what <em>would</em> have been stored, so a caller that announces
 * it (the signals feed) shows a state the store may not hold — readers fall back to the project
 * document in that case.
 */
@Service
@Slf4j
public class LiveStateStore {

    /** Pub/sub channel on which runner entries are announced to the brain pods. */
    public static final String RUNNER_CHANNEL = "runner-live";

    private static final String FIELD = "v";

    private final VanceRedisMessagingService redis;
    private final JsonMapper json = JsonMapper.builder().build();

    public LiveStateStore(VanceRedisMessagingService redis) {
        this.redis = redis;
    }

    /** A project's live entry. */
    public record ProjectEntry(ProjectLiveState state, long observedAtMs, long expiresAtMs) {}

    // ─── projects ───────────────────────────────────────────────────────

    /** Records what the project is doing now and returns the entry that was written. */
    public ProjectEntry putProject(String tenantId, String project, ProjectLiveState state, Duration ttl) {
        long now = Instant.now().toEpochMilli();
        ProjectEntry entry = new ProjectEntry(state, now, now + ttl.toMillis());
        try {
            redis.hashPut(tenantId, projectKey(project), FIELD, json.writeValueAsString(entry), ttl);
        } catch (RuntimeException e) {
            log.debug("live state write failed for '{}/{}': {}", tenantId, project, e.toString());
        }
        return entry;
    }

    /** The project's entry; empty when missing, expired or unreadable. */
    public Optional<ProjectEntry> getProject(String tenantId, String project) {
        return read(tenantId, projectKey(project), ProjectEntry.class)
                .filter(e -> e.expiresAtMs() > System.currentTimeMillis());
    }

    /**
     * Extends the life of an existing entry, keeping its state. Returns the renewed entry, or
     * empty when there was none to renew.
     */
    public Optional<ProjectEntry> renewProject(String tenantId, String project, Duration ttl) {
        return getProject(tenantId, project).map(e -> putProject(tenantId, project, e.state(), ttl));
    }

    // ─── runners ────────────────────────────────────────────────────────

    /**
     * Records the state of a runner and announces it on {@link #RUNNER_CHANNEL}. Written by
     * whoever manages the runner.
     */
    public RunnerStatusDto putRunner(
            String tenantId,
            String runnerId,
            RunnerPhase phase,
            int restarts,
            @Nullable Instant lastSeenAt,
            Duration ttl) {
        long now = Instant.now().toEpochMilli();
        RunnerStatusDto dto = RunnerStatusDto.builder()
                .runnerId(runnerId)
                .phase(phase)
                .restarts(restarts)
                .lastSeenAtMs(lastSeenAt == null ? null : lastSeenAt.toEpochMilli())
                .observedAtMs(now)
                .expiresAtMs(now + ttl.toMillis())
                .build();
        try {
            String body = json.writeValueAsString(dto);
            redis.hashPut(tenantId, runnerKey(runnerId), FIELD, body, ttl);
            redis.publish(tenantId, RUNNER_CHANNEL, body);
        } catch (RuntimeException e) {
            log.debug("live state write failed for runner '{}/{}': {}", tenantId, runnerId, e.toString());
        }
        return dto;
    }

    /** The runner's entry; empty when missing, expired or unreadable. */
    public Optional<RunnerStatusDto> getRunner(String tenantId, String runnerId) {
        return read(tenantId, runnerKey(runnerId), RunnerStatusDto.class)
                .filter(e -> e.getExpiresAtMs() != null && e.getExpiresAtMs() > System.currentTimeMillis());
    }

    /** Decodes a body announced on {@link #RUNNER_CHANNEL}; empty when it is not a runner entry. */
    public Optional<RunnerStatusDto> decodeRunner(String body) {
        try {
            return Optional.of(json.readValue(body, RunnerStatusDto.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ─── plumbing ───────────────────────────────────────────────────────

    private <T> Optional<T> read(String tenantId, String key, Class<T> type) {
        try {
            Map<String, String> fields = redis.hashGetAll(tenantId, key);
            String body = fields.get(FIELD);
            if (body == null) {
                return Optional.empty();
            }
            return Optional.of(json.readValue(body, type));
        } catch (RuntimeException e) {
            log.debug("live state read failed for '{}/{}': {}", tenantId, key, e.toString());
            return Optional.empty();
        }
    }

    static String projectKey(String project) {
        return "live:project:" + project;
    }

    static String runnerKey(String runnerId) {
        return "live:runner:" + runnerId;
    }
}
