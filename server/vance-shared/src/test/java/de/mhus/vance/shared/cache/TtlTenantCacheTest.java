package de.mhus.vance.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The reusable TTL cache that backs retention and the LLM budget settings.
 * What matters: a TTL genuinely removes loader calls, a {@code null} value is
 * cached as an entry, and {@link TtlTenantCache#invalidate(Object)} /
 * {@link TtlTenantCache#invalidateAll()} reset the entry.
 */
class TtlTenantCacheTest {

    @Test
    void repeatedReads_callTheLoaderOnce() {
        TtlTenantCache<String, String> cache = new TtlTenantCache<>(Duration.ofMinutes(1));
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 50; i++) {
            String v = cache.get("k", () -> {
                calls.incrementAndGet();
                return "value";
            });
            assertThat(v).isEqualTo("value");
        }

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void differentKeys_areSeparateEntries() {
        TtlTenantCache<String, Integer> cache = new TtlTenantCache<>(Duration.ofMinutes(1));

        assertThat(cache.get("a", () -> 1)).isEqualTo(1);
        assertThat(cache.get("b", () -> 2)).isEqualTo(2);
        // 'a' must still resolve from its own entry, not reload.
        assertThat(cache.get("a", () -> -1)).isEqualTo(1);
    }

    @Test
    void nullValue_isCachedAsAnEntry() {
        // The loader returns null to mean "no value"; the cache must not treat
        // a cached null as a miss and reload on every call.
        TtlTenantCache<String, String> cache = new TtlTenantCache<>(Duration.ofMinutes(1));
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            assertThat(cache.get("k", () -> {
                        calls.incrementAndGet();
                        return null;
                    }))
                    .isNull();
        }

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void invalidate_dropsTheEntry() {
        TtlTenantCache<String, Integer> cache = new TtlTenantCache<>(Duration.ofMinutes(1));
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", () -> {
            calls.incrementAndGet();
            return 1;
        });
        cache.invalidate("k");
        cache.get("k", () -> {
            calls.incrementAndGet();
            return 2;
        });

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void invalidateAll_dropsEveryEntry() {
        TtlTenantCache<String, Integer> cache = new TtlTenantCache<>(Duration.ofMinutes(1));
        AtomicInteger calls = new AtomicInteger();

        cache.get("a", () -> {
            calls.incrementAndGet();
            return 1;
        });
        cache.get("b", () -> {
            calls.incrementAndGet();
            return 2;
        });
        cache.invalidateAll();
        cache.get("a", () -> {
            calls.incrementAndGet();
            return 3;
        });

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void expiryReloads() throws InterruptedException {
        TtlTenantCache<String, Integer> cache = new TtlTenantCache<>(Duration.ofMillis(50));
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", () -> {
            calls.incrementAndGet();
            return 1;
        });
        Thread.sleep(80);
        cache.get("k", () -> {
            calls.incrementAndGet();
            return 2;
        });

        assertThat(calls.get()).isEqualTo(2);
    }
}
