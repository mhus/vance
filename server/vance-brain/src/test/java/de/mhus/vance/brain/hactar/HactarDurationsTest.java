package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HactarDurationsTest {

    @Test
    void parses_iso_8601_durations() {
        // ISO-8601 puts days before T: P7D for 7 days, PT5M30S for time-only.
        assertThat(HactarDurations.parse("P7D")).isEqualTo(Duration.ofDays(7));
        assertThat(HactarDurations.parse("PT5M30S")).isEqualTo(Duration.ofMinutes(5).plusSeconds(30));
        assertThat(HactarDurations.parse("PT0S")).isEqualTo(Duration.ZERO);
    }

    @Test
    void parses_day_shortcut() {
        assertThat(HactarDurations.parse("7d")).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void parses_hour_minute_second_shortcut() {
        assertThat(HactarDurations.parse("4h")).isEqualTo(Duration.ofHours(4));
        assertThat(HactarDurations.parse("30m")).isEqualTo(Duration.ofMinutes(30));
        assertThat(HactarDurations.parse("45s")).isEqualTo(Duration.ofSeconds(45));
        assertThat(HactarDurations.parse("250ms")).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void shortcut_is_case_insensitive_and_trimmed() {
        assertThat(HactarDurations.parse("  7D  ")).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void null_or_blank_input_rejected() {
        assertThatThrownBy(() -> HactarDurations.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HactarDurations.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknown_form_rejected() {
        assertThatThrownBy(() -> HactarDurations.parse("seven days"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }
}
