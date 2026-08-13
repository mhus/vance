package de.mhus.vance.brain.runs;

import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Every source's runs for one project, merged and sorted newest
     * first. A source that throws is logged and skipped — one broken
     * runtime must not blank the whole list.
     */
    public List<RunSummaryDto> list(String tenantId, String projectId, int limitPerSource) {
        List<RunSummaryDto> all = new ArrayList<>();
        for (RunSource source : byId.values()) {
            try {
                all.addAll(source.list(tenantId, projectId, limitPerSource));
            } catch (RuntimeException ex) {
                log.warn("RunSource '{}' failed to list runs for project '{}': {}",
                        source.sourceId(), projectId, ex.toString());
            }
        }
        all.sort(Comparator.comparing(
                RunSummaryDto::getStartedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return all;
    }

    /** One run by composite id; empty when the source or the run is unknown. */
    public Optional<RunDetailDto> get(String tenantId, String projectId, @Nullable String composite) {
        RunId id = RunId.parse(composite);
        if (id == null) return Optional.empty();
        RunSource source = byId.get(id.source());
        if (source == null) return Optional.empty();
        return source.get(tenantId, projectId, id.nativeId());
    }

    /** Registered source ids, in registration order. */
    public List<String> sourceIds() {
        return List.copyOf(byId.keySet());
    }
}
