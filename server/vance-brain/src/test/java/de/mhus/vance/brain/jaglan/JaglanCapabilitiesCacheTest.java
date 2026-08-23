package de.mhus.vance.brain.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code vance.jaglan.capabilities-ttl-seconds} has to actually be the TTL.
 *
 * <p>It used to be a documented knob wired to nothing: Caffeine bakes the
 * expiry into the instance and the builder ran in the field initialiser, i.e.
 * before {@code @Value} injection, with a hard-coded 30 minutes. An operator
 * setting it to 60 and watching a source that had switched to read-write stay
 * read-only for half an hour had no way to tell that from a broken source.
 */
class JaglanCapabilitiesCacheTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String MOUNT = "library";

    private static JaglanInstance instance() {
        JaglanInstance instance = mock(JaglanInstance.class);
        when(instance.mount()).thenReturn(MOUNT);
        when(instance.capabilities()).thenReturn(JaglanCapabilities.readOnly());
        return instance;
    }

    private static JaglanCapabilitiesCache cacheWithTtl(long seconds) {
        JaglanCapabilitiesCache cache = new JaglanCapabilitiesCache();
        ReflectionTestUtils.setField(cache, "ttlSeconds", seconds);
        cache.applyConfiguredTtl();
        return cache;
    }

    @Test
    void configuredTtl_isTheTtlTheCacheActuallyUses() {
        JaglanCapabilitiesCache cache = cacheWithTtl(0);

        assertThat(cache.warm(TENANT, PROJECT, instance())).isNotNull();

        // Zero means every read re-asks. With the property ignored the entry
        // would sit here for thirty minutes.
        assertThat(cache.peek(TENANT, PROJECT, MOUNT)).isNull();
    }

    @Test
    void aGenerousTtl_keepsTheDeclaration() {
        JaglanCapabilitiesCache cache = cacheWithTtl(1800);

        cache.warm(TENANT, PROJECT, instance());

        assertThat(cache.peek(TENANT, PROJECT, MOUNT)).isNotNull();
        assertThat(cache.configuredTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aNegativeTtl_fallsBackToTheDefaultInsteadOfFailingTheBoot() {
        JaglanCapabilitiesCache cache = cacheWithTtl(-5);

        assertThat(cache.configuredTtl()).isEqualTo(JaglanCapabilitiesCache.DEFAULT_TTL);
        cache.warm(TENANT, PROJECT, instance());
        assertThat(cache.peek(TENANT, PROJECT, MOUNT)).isNotNull();
    }

    @Test
    void evict_forgetsTheDeclarationAndTheFailureMemory() {
        JaglanCapabilitiesCache cache = cacheWithTtl(1800);
        cache.warm(TENANT, PROJECT, instance());

        cache.evict(TENANT, PROJECT, MOUNT);

        assertThat(cache.peek(TENANT, PROJECT, MOUNT)).isNull();
        assertThat(cache.failedRecently(TENANT, PROJECT, MOUNT)).isFalse();
    }
}
