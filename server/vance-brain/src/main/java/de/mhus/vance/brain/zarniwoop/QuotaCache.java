package de.mhus.vance.brain.zarniwoop;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchScope;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Project-scoped cache around
 * {@link SearchProviderInstance#currentQuota(SearchScope)}. The
 * {@code ZarniwoopService} consults this for the proactive
 * zero-quota gate (skip an instance whose quota is known to be empty
 * before firing a request that would just come back 429).
 *
 * <p>TTL is short (10 min default) and per-entry; project suspend
 * evicts the whole project. The cache deliberately stores
 * {@link Optional#empty()} too — instances that don't expose a quota
 * endpoint should not be probed once per call.
 */
@Service
@Slf4j
public class QuotaCache {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final Cache<Key, Optional<QuotaStatus>> cache;

    public QuotaCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(DEFAULT_TTL)
                .maximumSize(1024)
                .build();
    }

    /**
     * Return the cached quota for {@code instance} in {@code scope} or
     * refresh it from the instance on cache miss. Project must be set
     * — the cache key is per-(tenant, project, instance).
     */
    public Optional<QuotaStatus> get(SearchProviderInstance instance, SearchScope scope) {
        if (instance == null || scope == null
                || StringUtils.isBlank(scope.projectId())
                || StringUtils.isBlank(instance.id())) {
            return Optional.empty();
        }
        Key key = new Key(scope.tenantId(), scope.projectId(), instance.id());
        return cache.get(key, k -> safeProbe(instance, scope));
    }

    /** Force a refresh on next get(). Test/debug hook. */
    public void invalidate(SearchProviderInstance instance, SearchScope scope) {
        if (instance == null || scope == null || StringUtils.isBlank(scope.projectId())) return;
        cache.invalidate(new Key(scope.tenantId(), scope.projectId(), instance.id()));
    }

    @EventListener
    public void onProjectStop(ProjectEnginesStopRequested event) {
        if (event == null || StringUtils.isBlank(event.tenantId())
                || StringUtils.isBlank(event.projectName())) {
            return;
        }
        cache.asMap().keySet().removeIf(k ->
                k.tenantId.equals(event.tenantId())
                        && k.projectId.equals(event.projectName()));
        log.debug("QuotaCache: evicted entries for '{}/{}' on project stop",
                event.tenantId(), event.projectName());
    }

    private static Optional<QuotaStatus> safeProbe(SearchProviderInstance instance,
                                                   SearchScope scope) {
        try {
            return instance.currentQuota(scope);
        } catch (RuntimeException e) {
            log.debug("QuotaCache: probe of '{}' raised: {}", instance.id(), e.toString());
            return Optional.empty();
        }
    }

    record Key(String tenantId, String projectId, String instanceId) { }
}
