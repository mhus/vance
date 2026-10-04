package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The D10 catch-up semantics live in {@code nextDue}: recurring schedules
 * re-anchor from <b>now</b>, so a missed run never accumulates — and the
 * cadence floor keeps a self-check schedule from becoming a spin-loop.
 */
class TrillianScheduleStoreTest {

    @Test
    void parsesEveryToSeconds() {
        assertThat(TrillianScheduleStore.parseEverySeconds("5m")).isEqualTo(300);
        assertThat(TrillianScheduleStore.parseEverySeconds("2h")).isEqualTo(7200);
        assertThat(TrillianScheduleStore.parseEverySeconds("1d")).isEqualTo(86400);
        assertThat(TrillianScheduleStore.parseEverySeconds(" 30M ")).isEqualTo(1800);
    }

    @Test
    void rejectsUnparseableEvery() {
        assertThatThrownBy(() -> TrillianScheduleStore.parseEverySeconds(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrillianScheduleStore.parseEverySeconds(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrillianScheduleStore.parseEverySeconds("12"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrillianScheduleStore.parseEverySeconds("0m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrillianScheduleStore.parseEverySeconds("xm"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextDueAnchorsFromNowNotFromTheMissedSlot() {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");
        // "due was 08:00, every 1h, it is 10:00" → next is 11:00, not 09:00
        // and not 12:00. Two missed runs do not pile up (D10).
        assertThat(TrillianScheduleStore.nextDue("1h", now)).isEqualTo(Instant.parse("2026-10-05T11:00:00Z"));
    }

    @Test
    void cadenceFloorIsFiveMinutes() {
        assertThat(TrillianScheduleStore.MIN_EVERY_SECONDS).isEqualTo(300);
        // The floor is the tool's job to enforce; the parse layer only
        // knows the grammar. This pins the constant the tools use.
        assertThat(TrillianScheduleStore.parseEverySeconds("5m"))
                .isGreaterThanOrEqualTo(TrillianScheduleStore.MIN_EVERY_SECONDS);
    }

    @Test
    void schedulePathLivesUnderItsOwnFolder() {
        // Deliberately NOT _vance/scheduler/ — Ursa's loader scans that
        // folder and would try to fire these as Ursa schedules.
        assertThat(TrillianScheduleStore.pathFor("standup")).isEqualTo("_vance/trillian/schedules/standup.yaml");
    }
}
