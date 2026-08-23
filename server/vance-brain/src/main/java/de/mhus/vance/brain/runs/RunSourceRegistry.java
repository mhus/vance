package de.mhus.vance.brain.runs;

import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Collects the {@link RunSource} beans and fans queries out over them —
 * the same shape as {@code KindRegistry}: Spring injects the list,
 * the registry keys it by name, callers never see the individual beans.
 *
 * <p>Sources are optional by construction. Magrathea is behind a feature
 * flag, compose is not always in play, and an addon may bring one of its
 * own; a missing source simply contributes no rows.
 */
@Service
@Slf4j
public class RunSourceRegistry {

    private final List<RunSource> sources;
    private Map<String, RunSource> byId = Map.of();

    public RunSourceRegistry(List<RunSource> sources) {
        this.sources = sources;
    }

    @PostConstruct
    void collect() {
        Map<String, RunSource> map = new LinkedHashMap<>();
        for (RunSource source : sources) {
            String id = source.sourceId();
            if (id == null || id.isBlank() || id.contains(":")) {
                throw new IllegalStateException(
                        "RunSource " + source.getClass().getName()
                                + " has an unusable sourceId '" + id + "'");
            }
            RunSource previous = map.putIfAbsent(id, source);
            if (previous != null) {
                log.warn("Duplicate RunSource '{}' — keeping {}, ignoring {}",
                        id, previous.getClass().getName(), source.getClass().getName());
            }
        }
        this.byId = Map.copyOf(map);
        log.info("RunSourceRegistry: {} source(s) {}", byId.size(), byId.keySet());
    }

    /**
     * The newest {@code limit} runs of one project across every source.
     *
     * <p>Each source is asked for up to {@code limit} of its own, the
     * results are merged newest-first and the tail is cut. The cut is the
     * point: without it {@code limit} meant "per source", so the caller
     * that asked for 50 got 50 times however many runtimes happen to be
     * registered — a number it cannot know and did not ask for.
     *
     * <p>A source that throws is logged and skipped — one broken runtime
     * must not blank the whole list.
     */
    public List<RunSummaryDto> list(
            de.mhus.vance.shared.permission.SecurityContext subject,
            String tenantId, String projectId, int limit) {
        List<RunSummaryDto> all = new ArrayList<>();
        for (RunSource source : byId.values()) {
            try {
                for (RunSummaryDto run : source.list(tenantId, projectId, limit)) {
                    RunId runId = RunId.parse(run.getRunId());
                    if (runId != null && !source.visibleTo(
                            subject, tenantId, projectId, runId.nativeId())) {
                        continue;
                    }
                    all.add(run);
                }
            } catch (RuntimeException ex) {
                log.warn("RunSource '{}' failed to list runs for project '{}': {}",
                        source.sourceId(), projectId, ex.toString());
            }
        }
        all.sort(Comparator.comparing(
                RunSummaryDto::getStartedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
    }

    /** One run by composite id; empty when the source or the run is unknown. */
    public Optional<RunDetailDto> get(
            de.mhus.vance.shared.permission.SecurityContext subject,
            String tenantId, String projectId, @Nullable String composite) {
        RunId id = RunId.parse(composite);
        if (id == null) return Optional.empty();
        RunSource source = byId.get(id.source());
        if (source == null) return Optional.empty();
        // Reported as absent rather than forbidden, like every other
        // out-of-scope lookup here: existence is not leaked either way.
        if (!source.visibleTo(subject, tenantId, projectId, id.nativeId())) {
            return Optional.empty();
        }
        return source.get(tenantId, projectId, id.nativeId());
    }

    /**
     * What may be done to this run right now — routed to its source, and
     * empty for anything unknown so a caller cannot learn what exists.
     *
     * <p>Invisible counts as unknown: a run the subject may not see offers
     * no verbs, for the same reason {@link #get} reports it as absent.
     */
    public Set<RunAction> allowedActions(
            de.mhus.vance.shared.permission.SecurityContext subject,
            String tenantId, String projectId, @Nullable String composite) {
        RunId id = RunId.parse(composite);
        if (id == null) return Set.of();
        RunSource source = byId.get(id.source());
        if (source == null) return Set.of();
        if (!source.visibleTo(subject, tenantId, projectId, id.nativeId())) return Set.of();
        return source.allowedActions(tenantId, projectId, id.nativeId());
    }

    /**
     * Perform an action on a run.
     *
     * <p>Filtered by {@code visibleTo} like every read: a source that hides
     * a run from this subject must not act on it for them either — an
     * invisible run that can still be stopped is the worst of both, the
     * effect lands and the response is a 404. Indistinguishable from
     * "unknown" on purpose.
     *
     * @throws IllegalArgumentException when the id names no known source,
     *         the source does not know the run, or the subject may not see it
     */
    public void perform(de.mhus.vance.shared.permission.SecurityContext subject,
                        String tenantId, String projectId, @Nullable String composite,
                        RunAction action, String reason) {
        RunId id = RunId.parse(composite);
        if (id == null) throw new IllegalArgumentException("Malformed run id: " + composite);
        RunSource source = byId.get(id.source());
        if (source == null) throw new IllegalArgumentException("Unknown run source in: " + composite);
        if (!source.visibleTo(subject, tenantId, projectId, id.nativeId())) {
            throw new IllegalArgumentException("No such run: " + composite);
        }
        source.perform(tenantId, projectId, id.nativeId(), action, reason);
    }

    /** Registered source ids, in registration order. */
    public List<String> sourceIds() {
        return List.copyOf(byId.keySet());
    }
}
