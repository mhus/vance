package de.mhus.vance.brain.workspace.access;

import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory cache that maps {@code (tenant, project)} to the owner pod's IP.
 * Entries are populated lazily from {@link ProjectService} and dropped on
 * connect failures so a pod that loses its claim is forgotten on the next
 * miss. See {@code specification/workspace-access.md} §4.
 */
@Component
@Slf4j
public class WorkspaceRoutingCache {

    record PodEntry(String podIp, Instant lastUsed) {
    }

    private final ConcurrentMap<ProjectPodKey, PodEntry> entries = new ConcurrentHashMap<>();
    private final ProjectService projectService;
    private final Duration ttl;

    public WorkspaceRoutingCache(ProjectService projectService, WorkspaceAccessProperties properties) {
        this.projectService = projectService;
        this.ttl = properties.getCacheTtl();
    }

    /**
     * Resolve the owner pod IP for a project. Returns empty when the project
     * does not exist or has not been claimed yet. Does not validate
     * reachability — callers handle that and call {@link #invalidate} on
     * connect failure.
     */
    public Optional<String> lookup(ProjectPodKey key) {
        Instant now = Instant.now();
        PodEntry cached = entries.get(key);
        if (cached != null && !isExpired(cached, now)) {
            entries.put(key, new PodEntry(cached.podIp(), now));
            return Optional.of(cached.podIp());
        }
        Optional<String> fresh = readFromMongo(key);
        fresh.ifPresent(ip -> entries.put(key, new PodEntry(ip, now)));
        if (fresh.isEmpty()) {
            entries.remove(key);
        }
        return fresh;
    }

    /**
     * Force a fresh Mongo read, bypassing the cache. Used after a connect
     * failure to give the routing one more shot before giving up.
     */
    public Optional<String> refresh(ProjectPodKey key) {
        entries.remove(key);
        Optional<String> fresh = readFromMongo(key);
        fresh.ifPresent(ip -> entries.put(key, new PodEntry(ip, Instant.now())));
        return fresh;
    }

    /** Drop the cached entry — typically after a connect failure. */
    public void invalidate(ProjectPodKey key) {
        entries.remove(key);
    }

    private Optional<String> readFromMongo(ProjectPodKey key) {
        Optional<ProjectDocument> doc = projectService.findByTenantAndName(key.tenantId(), key.projectName());
        if (doc.isEmpty()) {
            return Optional.empty();
        }
        String podIp = doc.get().getPodIp();
        if (podIp == null || podIp.isBlank()) {
            log.debug("Project {}/{} exists but has no podIp — not yet claimed by any pod",
                    key.tenantId(), key.projectName());
            return Optional.empty();
        }
        return Optional.of(podIp);
    }

    private boolean isExpired(PodEntry entry, Instant now) {
        if (ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return entry.lastUsed().plus(ttl).isBefore(now);
    }
}
