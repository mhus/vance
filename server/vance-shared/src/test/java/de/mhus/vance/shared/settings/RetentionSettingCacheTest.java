package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The cache that keeps the retention lookup off the write path. What matters
 * is that it actually removes round-trips, that it does not confuse scopes, and
 * that a broken value still yields the fallback rather than an exception.
 */
class RetentionSettingCacheTest {

    private static final String KEY = "megadodo.retentionDays";

    private SettingService settingService;
    private RetentionSettingCache cache;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        cache = new RetentionSettingCache(settingService);
    }

    @Test
    void repeatedLookups_hitTheSettingsCascadeOnce() {
        // The whole point: this runs per feed row and twice per model-call
        // attempt, and the cascade is up to three uncached Mongo reads.
        when(settingService.getStringValueCascade(eq("acme"), eq("proj"), any(), eq(KEY)))
                .thenReturn("30");

        for (int i = 0; i < 50; i++) {
            assertThat(cache.days("acme", "proj", KEY, 90)).isEqualTo(30);
        }

        verify(settingService, times(1))
                .getStringValueCascade(eq("acme"), eq("proj"), any(), eq(KEY));
    }

    @Test
    void differentScopes_areSeparateEntries() {
        when(settingService.getStringValueCascade(eq("acme"), eq("a"), any(), eq(KEY)))
                .thenReturn("10");
        when(settingService.getStringValueCascade(eq("acme"), eq("b"), any(), eq(KEY)))
                .thenReturn("20");

        assertThat(cache.days("acme", "a", KEY, 90)).isEqualTo(10);
        assertThat(cache.days("acme", "b", KEY, 90)).isEqualTo(20);
    }

    @Test
    void tenantScope_isNotTheSameEntryAsAProjectNamedEmpty() {
        // The key is built by concatenation; a separator that can also be a
        // value would make ("acme", null) and ("acme", "") collide with a
        // differently-keyed third combination.
        when(settingService.getStringValueCascade(eq("acme"), eq(null), any(), eq(KEY)))
                .thenReturn("5");
        when(settingService.getStringValueCascade(eq("acme"), eq("proj"), any(), eq(KEY)))
                .thenReturn("7");

        assertThat(cache.days("acme", null, KEY, 90)).isEqualTo(5);
        assertThat(cache.days("acme", "proj", KEY, 90)).isEqualTo(7);
    }

    @Test
    void triStateIsPassedThroughUntouched() {
        // 0 = keep forever, negative = do not write. Interpreting them is the
        // caller's business; the cache must not clamp or normalise.
        when(settingService.getStringValueCascade(eq("acme"), any(), any(), eq(KEY)))
                .thenReturn("0");
        assertThat(cache.days("acme", "p1", KEY, 90)).isZero();

        when(settingService.getStringValueCascade(eq("acme"), any(), any(), eq(KEY)))
                .thenReturn("-1");
        assertThat(cache.days("acme", "p2", KEY, 90)).isEqualTo(-1);
    }

    @Test
    void unparseableValue_fallsBackInsteadOfThrowing() {
        when(settingService.getStringValueCascade(eq("acme"), any(), any(), eq(KEY)))
                .thenReturn("soon");

        assertThat(cache.days("acme", "proj", KEY, 90)).isEqualTo(90);
    }

    @Test
    void unsetValue_fallsBack() {
        when(settingService.getStringValueCascade(eq("acme"), any(), any(), eq(KEY)))
                .thenReturn(null);

        assertThat(cache.days("acme", "proj", KEY, 90)).isEqualTo(90);
    }

    @Test
    void invalidate_forcesAFreshRead() {
        when(settingService.getStringValueCascade(eq("acme"), any(), any(), eq(KEY)))
                .thenReturn("30");
        assertThat(cache.days("acme", "proj", KEY, 90)).isEqualTo(30);

        when(settingService.getStringValueCascade(eq("acme"), any(), any(), eq(KEY)))
                .thenReturn("60");
        cache.invalidate();

        assertThat(cache.days("acme", "proj", KEY, 90)).isEqualTo(60);
    }
}
