package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Pod-local invocation counter the {@code ZarniwoopService} updates on
 * every dispatch. Powers the Insights tab — the audit-doc log in
 * {@code _vance/logs/research/} remains the persistent source of truth,
 * the counter is throw-away state.
 *
 * <p>Project-scoped so {@code ProjectEnginesStopRequested} drops the
 * project's entries the same way the factory cache does. Cross-pod
 * aggregation is not a v1 goal — Insights shows what this pod has
 * served since it started.
 */
@Service
@Slf4j
public class ZarniwoopUsageCounter {

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    /** Record one search dispatch. */
    public void recordSuccess(SearchScope scope, String instanceId, SearchModality modality) {
        Entry e = entryFor(scope, instanceId);
        if (e == null) return;
        e.totalCalls.incrementAndGet();
        e.okCalls.incrementAndGet();
        e.lastUsedAt = Instant.now();
        e.touchModality(modality);
    }

    /** Record one hard upstream failure. */
    public void recordError(SearchScope scope, String instanceId,
                            SearchModality modality, @Nullable String message) {
        Entry e = entryFor(scope, instanceId);
        if (e == null) return;
        e.totalCalls.incrementAndGet();
        e.errorCalls.incrementAndGet();
        e.lastErrorAt = Instant.now();
        e.lastErrorMessage = message;
        e.touchModality(modality);
    }

    /** Read-only snapshot for the insights endpoint. */
    public Snapshot snapshotFor(String tenantId, String projectId, String instanceId) {
        Entry e = entries.get(new Key(tenantId, projectId, instanceId));
        if (e == null) {
            return new Snapshot(0L, 0L, 0L, null, null, null, List.of());
        }
        return new Snapshot(
                e.totalCalls.get(),
                e.okCalls.get(),
                e.errorCalls.get(),
                e.lastUsedAt,
                e.lastErrorAt,
                e.lastErrorMessage,
                new ArrayList<>(e.modalitiesSeen));
    }

    @EventListener
    public void onProjectStop(ProjectEnginesStopRequested event) {
        if (event == null || StringUtils.isBlank(event.tenantId())
                || StringUtils.isBlank(event.projectName())) {
            return;
        }
        entries.keySet().removeIf(k ->
                k.tenantId.equals(event.tenantId())
                        && k.projectId.equals(event.projectName()));
        log.debug("ZarniwoopUsageCounter: cleared entries for '{}/{}' on project stop",
                event.tenantId(), event.projectName());
    }

    private @Nullable Entry entryFor(SearchScope scope, String instanceId) {
        if (scope == null || StringUtils.isBlank(scope.projectId())
                || StringUtils.isBlank(instanceId)) {
            return null;
        }
        return entries.computeIfAbsent(
                new Key(scope.tenantId(), scope.projectId(), instanceId),
                k -> new Entry());
    }

    public record Snapshot(
            long total,
            long ok,
            long error,
            @Nullable Instant lastUsedAt,
            @Nullable Instant lastErrorAt,
            @Nullable String lastErrorMessage,
            List<String> modalitiesSeen) { }

    private record Key(String tenantId, String projectId, String instanceId) { }

    private static final class Entry {
        final AtomicLong totalCalls = new AtomicLong();
        final AtomicLong okCalls = new AtomicLong();
        final AtomicLong errorCalls = new AtomicLong();
        volatile @Nullable Instant lastUsedAt;
        volatile @Nullable Instant lastErrorAt;
        volatile @Nullable String lastErrorMessage;
        // ConcurrentHashMap.newKeySet() — we only need contains+add+iterate
        final java.util.Set<String> modalitiesSeen =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        void touchModality(SearchModality modality) {
            if (modality != null) modalitiesSeen.add(modality.name());
        }
    }
}
