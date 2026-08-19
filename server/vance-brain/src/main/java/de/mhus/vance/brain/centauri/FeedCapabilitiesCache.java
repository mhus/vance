package de.mhus.vance.brain.centauri;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Keyed by source id only, <b>not</b> by reader: capabilities describe the
 * source, not the person reading. That is also why no pseudonym travels on the
 * call that fetches them.
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
    public FeedCapabilities get(FeedSourceInstance instance) {
        FeedCapabilities cached = cache.getIfPresent(instance.id());
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
        cache.put(instance.id(), fresh);
        return fresh;
    }

    public void invalidate(@Nullable List<String> sourceIds) {
        if (sourceIds == null) {
            cache.invalidateAll();
            return;
        }
        cache.invalidateAll(sourceIds);
    }
}
