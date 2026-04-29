package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.ProcessListRequest;
import de.mhus.vance.api.thinkprocess.ProcessListResponse;
import de.mhus.vance.api.thinkprocess.ProcessSummary;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectGroupListResponse;
import de.mhus.vance.api.ws.ProjectGroupSummary;
import de.mhus.vance.api.ws.ProjectListRequest;
import de.mhus.vance.api.ws.ProjectListResponse;
import de.mhus.vance.api.ws.ProjectSummary;
import de.mhus.vance.api.ws.SessionListRequest;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.foot.connection.ConnectionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Lazy + sticky cache for tab-completion of dynamic argument kinds
 * ({@link ArgKind#PROCESS}, {@link ArgKind#SESSION}, {@link ArgKind#PROJECT},
 * {@link ArgKind#PROJECT_GROUP}). Each source has its own bucket with a
 * TTL tuned to how fast the underlying data churns:
 *
 * <ul>
 *   <li>{@code processes} — 15s, sub-engines spawn during the user's turn</li>
 *   <li>{@code sessions} — 60s</li>
 *   <li>{@code projects} / {@code projectGroups} — 5 minutes, near-static</li>
 * </ul>
 *
 * <p><b>Strategy.</b> A read returns whatever is in the bucket immediately
 * (stale or fresh), and kicks off an async refresh when the entry has
 * aged past its TTL. The first read while empty therefore returns an
 * empty list — the user sees nothing on the first Tab, suggestions
 * appear on the next Tab a moment later. Better than blocking the JLine
 * thread on a WebSocket round-trip.
 *
 * <p><b>Opportunistic refill.</b> The {@code rememberX(...)} mutators are
 * called by the matching {@code /…-list} commands after every successful
 * response — the cache rides on traffic the user is already producing.
 * {@link SessionService#bind(String, String)} / {@link SessionService#clear()}
 * call {@link #invalidateAll()} so a session switch doesn't show stale
 * processes from the previous session.
 *
 * <p>{@link ConnectionService} is injected lazily — fetches gracefully no-op
 * when the connection is closed.
 */
@Service
@Slf4j
public class SuggestionCache {

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

    private final ConnectionService connection;

    private final Bucket processes = new Bucket(Duration.ofSeconds(15));
    private final Bucket sessions = new Bucket(Duration.ofSeconds(60));
    private final Bucket projects = new Bucket(Duration.ofMinutes(5));
    private final Bucket projectGroups = new Bucket(Duration.ofMinutes(5));

    public SuggestionCache(@Lazy ConnectionService connection) {
        this.connection = connection;
    }

    public List<String> processes() {
        return processes.read(this::fetchProcesses);
    }

    public List<String> sessions() {
        return sessions.read(this::fetchSessions);
    }

    public List<String> projects() {
        return projects.read(this::fetchProjects);
    }

    public List<String> projectGroups() {
        return projectGroups.read(this::fetchProjectGroups);
    }

    public void rememberProcesses(List<String> names) {
        processes.set(names);
    }

    public void rememberSessions(List<String> ids) {
        sessions.set(ids);
    }

    public void rememberProjects(List<String> names) {
        projects.set(names);
    }

    public void rememberProjectGroups(List<String> names) {
        projectGroups.set(names);
    }

    /** Drop all caches — called on session bind / clear. */
    public void invalidateAll() {
        processes.invalidate();
        sessions.invalidate();
        projects.invalidate();
        projectGroups.invalidate();
    }

    // ───────────────────────────────────────────────── fetchers

    private List<String> fetchProcesses() throws Exception {
        if (!connection.isOpen()) return List.of();
        ProcessListResponse resp = connection.request(
                MessageType.PROCESS_LIST,
                ProcessListRequest.builder().includeTerminated(false).build(),
                ProcessListResponse.class,
                FETCH_TIMEOUT);
        return resp.getProcesses() == null
                ? List.of()
                : resp.getProcesses().stream().map(ProcessSummary::getName).filter(s -> s != null && !s.isBlank()).toList();
    }

    private List<String> fetchSessions() throws Exception {
        if (!connection.isOpen()) return List.of();
        SessionListResponse resp = connection.request(
                MessageType.SESSION_LIST,
                SessionListRequest.builder().build(),
                SessionListResponse.class,
                FETCH_TIMEOUT);
        return resp.getSessions() == null
                ? List.of()
                : resp.getSessions().stream().map(SessionSummary::getSessionId).filter(s -> s != null && !s.isBlank()).toList();
    }

    private List<String> fetchProjects() throws Exception {
        if (!connection.isOpen()) return List.of();
        ProjectListResponse resp = connection.request(
                MessageType.PROJECT_LIST,
                ProjectListRequest.builder().build(),
                ProjectListResponse.class,
                FETCH_TIMEOUT);
        return resp.getProjects() == null
                ? List.of()
                : resp.getProjects().stream().map(ProjectSummary::getName).filter(s -> s != null && !s.isBlank()).toList();
    }

    private List<String> fetchProjectGroups() throws Exception {
        if (!connection.isOpen()) return List.of();
        ProjectGroupListResponse resp = connection.request(
                MessageType.PROJECTGROUP_LIST,
                null,
                ProjectGroupListResponse.class,
                FETCH_TIMEOUT);
        return resp.getGroups() == null
                ? List.of()
                : resp.getGroups().stream().map(ProjectGroupSummary::getName).filter(s -> s != null && !s.isBlank()).toList();
    }

    // ───────────────────────────────────────────────── bucket

    /**
     * One cached list with TTL and idempotent async refresh. Returns
     * the most recent value immediately on every read; kicks off a
     * single background fetch when the entry is expired or absent and
     * no fetch is already in flight.
     */
    private static final class Bucket {

        private final Duration ttl;
        private final AtomicReference<@Nullable Cached> ref = new AtomicReference<>();
        private final AtomicBoolean fetching = new AtomicBoolean(false);

        Bucket(Duration ttl) {
            this.ttl = ttl;
        }

        List<String> read(FetcherEx fetcher) {
            Cached current = ref.get();
            boolean fresh = current != null
                    && Duration.between(current.at, Instant.now()).compareTo(ttl) < 0;
            if (!fresh && fetching.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> {
                    try {
                        List<String> values = fetcher.get();
                        ref.set(new Cached(values, Instant.now()));
                    } catch (Exception e) {
                        // best-effort; the next read kicks another attempt
                        log.debug("suggestion fetch failed: {}", e.getMessage());
                    } finally {
                        fetching.set(false);
                    }
                });
            }
            return current == null ? List.of() : current.values;
        }

        void set(List<String> values) {
            ref.set(new Cached(List.copyOf(values), Instant.now()));
        }

        void invalidate() {
            ref.set(null);
        }
    }

    @FunctionalInterface
    private interface FetcherEx {
        List<String> get() throws Exception;
    }

    private record Cached(List<String> values, Instant at) {}
}
