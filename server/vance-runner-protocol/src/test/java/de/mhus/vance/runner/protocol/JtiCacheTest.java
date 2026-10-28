package de.mhus.vance.runner.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JtiCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void aJti_isFirstUsedOnce_untilItExpires() {
        JtiCache cache = new JtiCache(10);

        assertThat(cache.firstUse("a", NOW.plusSeconds(60), NOW)).isTrue();
        assertThat(cache.firstUse("a", NOW.plusSeconds(60), NOW.plusSeconds(30)))
                .isFalse();
        assertThat(cache.firstUse("a", NOW.plusSeconds(120), NOW.plusSeconds(90)))
                .as("the earlier use has expired — a token this old would be refused as expired anyway")
                .isTrue();
    }

    @Test
    void expiredEntries_makeRoom_butAFullCacheOfValidOnesFailsClosed() {
        JtiCache cache = new JtiCache(2);
        cache.firstUse("a", NOW.plusSeconds(10), NOW);
        cache.firstUse("b", NOW.plusSeconds(10), NOW);

        assertThat(cache.firstUse("c", NOW.plusSeconds(100), NOW.plusSeconds(20)))
                .isTrue();
        assertThat(cache.size()).isEqualTo(1);

        cache.firstUse("d", NOW.plusSeconds(100), NOW.plusSeconds(20));
        assertThatThrownBy(() -> cache.firstUse("e", NOW.plusSeconds(100), NOW.plusSeconds(20)))
                .isInstanceOfSatisfying(
                        RunnerProtocolException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TOKEN));
    }

    @Test
    void aNonPositiveSize_isRefused() {
        assertThatThrownBy(() -> new JtiCache(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
