package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prak.PrakProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The compaction-trigger threshold selection. Triggering too late overflows
 * the context window (the call fails); triggering too eagerly runs an
 * unnecessary destructive compaction. Pins the NONE/SOFT/HARD/EMERGENCY
 * boundaries (inclusive {@code >=}), the zero-window guard, and the cheap
 * token estimator.
 */
class CompactionTriggerServiceTest {

    private CompactionTriggerService service;

    @BeforeEach
    void setUp() {
        PrakProperties props = new PrakProperties();
        // Explicit thresholds so the test is independent of default drift.
        props.setCompactionSoftThreshold(0.40);
        props.setCompactionHardThreshold(0.85);
        props.setCompactionEmergencyThreshold(0.95);
        service = new CompactionTriggerService(props);
    }

    @Test
    void nonPositiveContextWindow_isNone() {
        assertThat(service.evaluate(500, 0)).isEqualTo(CompactionMode.NONE);
        assertThat(service.evaluate(500, -1)).isEqualTo(CompactionMode.NONE);
    }

    @Test
    void belowSoftThreshold_isNone() {
        assertThat(service.evaluate(399, 1000)).isEqualTo(CompactionMode.NONE);
    }

    @Test
    void atAndAboveSoft_belowHard_isSoft() {
        assertThat(service.evaluate(400, 1000)).isEqualTo(CompactionMode.SOFT); // == soft, inclusive
        assertThat(service.evaluate(840, 1000)).isEqualTo(CompactionMode.SOFT);
    }

    @Test
    void atAndAboveHard_belowEmergency_isHard() {
        assertThat(service.evaluate(850, 1000)).isEqualTo(CompactionMode.HARD); // == hard, inclusive
        assertThat(service.evaluate(940, 1000)).isEqualTo(CompactionMode.HARD);
    }

    @Test
    void atAndAboveEmergency_isEmergency() {
        assertThat(service.evaluate(950, 1000)).isEqualTo(CompactionMode.EMERGENCY); // == emergency
        assertThat(service.evaluate(2000, 1000)).isEqualTo(CompactionMode.EMERGENCY); // over 100%
    }

    // ──────────────── token estimator ────────────────

    @Test
    void estimateTokens_nullOrEmpty_isZero() {
        assertThat(service.estimateTokens(null)).isZero();
        assertThat(service.estimateTokens(List.of())).isZero();
    }

    @Test
    void estimateTokens_scalesWithContent() {
        List<ChatMessage> few = List.of(UserMessage.from("hi"));
        List<ChatMessage> many = List.of(
                UserMessage.from("a considerably longer message with more characters"),
                UserMessage.from("and a second one on top"));

        assertThat(service.estimateTokens(few)).isPositive();
        assertThat(service.estimateTokens(many)).isGreaterThan(service.estimateTokens(few));
    }
}
