package de.mhus.vance.brain.centauri;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Caches what each source says it can do, for as long as that source says.
 *
 * <p>Lives here rather than in each protocol so three implementations do not
 * each invent their own caching, and lives at all because the dispatcher needs
 * the capabilities on every page — to decide what to push down and how far to
 * over-fetch — while an HTTP-backed source would otherwise be asked twice per
 * page.
 *
 * <p>Keyed by <b>(tenant, project, source id)</b> and <b>not</b> by reader.
 * Capabilities describe the source, not the person reading, which is why no
 * pseudonym travels on the call that fetches them — but the source id alone is
 * not an identity. It is the endpoint name from
 * {@code centauri.endpoint.<id>.*}, so two projects naming an endpoint after
 * its job — {@code archive}, {@code news} — are two different services under
 * one key. {@link FeedSourceFactory} is scoped per project for exactly that
 * reason; a cache in front of it has to be scoped the same way or it hands one
 * project's declaration to another, deciding there what may be pushed down and
 * which back-channel signals exist.
 */
@Service
@Slf4j
public class FeedCapabilitiesCache {

    private final Cache<String, FeedCapabilities> cache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfter(new Expiry<String, FeedCapabilities>() {

                @Override
                public long expireAfterCreate(String key, FeedCapabilities value, long now) {
                    return value.capabilitiesTtl().toNanos();
                }

                @Override
                public long expireAfterUpdate(String key, FeedCapabilities value, long now,
                                              long currentDuration) {
                    return value.capabilitiesTtl().toNanos();
                }

                @Override
                public long expireAfterRead(String key, FeedCapabilities value, long now,
                                            long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    /**
     * The source's capabilities, asking it at most once per its own TTL.
     *
     * <p>A source that cannot answer is not fatal: the fallback is the most
     * pessimistic set — nothing pushes down, no back channel — which costs
     * post-filtering and hides buttons rather than producing wrong results.
     */
    public FeedCapabilities get(FeedScope scope, FeedSourceInstance instance) {
        String key = key(scope, instance.id());
        FeedCapabilities cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        FeedCapabilities fresh;
        try {
            fresh = instance.capabilities();
        } catch (RuntimeException e) {
            log.warn("Centauri: source '{}' could not state its capabilities, "
                    + "assuming the pessimistic set: {}", instance.id(), e.toString());
            return FeedCapabilities.enumerableReadOnly(FeedCapabilities.DEFAULT_MAX_PAGE_SIZE);
        }
        cache.put(key, fresh);
        return fresh;
    }

    /**
     * Drop cached declarations for one project — {@code sourceIds} null meaning
     * all of them.
     *
     * <p>Scoped, so an operator refreshing one project does not reset the
     * others. Pairs with {@link FeedSourceFactory#evict}: rebuilding the
     * instances without dropping their declarations would leave the two views
     * disagreeing about the same endpoint.
     */
    public void invalidate(FeedScope scope, @Nullable List<String> sourceIds) {
        if (scope == null || StringUtils.isBlank(scope.projectId())) {
            return;
        }
        if (sourceIds == null) {
            String prefix = scopePrefix(scope);
            cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
            return;
        }
        for (String sourceId : sourceIds) {
            cache.invalidate(key(scope, sourceId));
        }
    }

    /**
     * {@code tenant/project/id}. The separator cannot appear in a tenant or
     * project name, so two different scopes cannot produce one key.
     */
    private static String key(FeedScope scope, String sourceId) {
        return scopePrefix(scope) + sourceId;
    }

    private static String scopePrefix(FeedScope scope) {
        return StringUtils.defaultString(scope.tenantId()) + '/'
                + StringUtils.defaultString(scope.projectId()) + '/';
    }
}
