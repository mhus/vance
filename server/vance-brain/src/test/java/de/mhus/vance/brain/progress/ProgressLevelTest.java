package de.mhus.vance.brain.progress;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.progress.ProgressKind;
import de.mhus.vance.api.progress.StatusTag;
import org.junit.jupiter.api.Test;

/**
 * The verbosity filter of the progress side-channel.
 *
 * <p>Pinned here: engine turn boundaries survive {@code progress: off}.
 * Since the persist-bound steer ack
 * ({@code planning/active-message-queue.md} §2) they are the only signal
 * that a turn is in flight — foot's busy spinner, the one-shot turn gate
 * and remote drivers all track turns from them, and a verbosity setting
 * that silences them makes "is the engine working?" unanswerable.
 */
class ProgressLevelTest {

    @Test
    void turnBoundaries_passEvenAtOff() {
        assertThat(ProgressLevel.OFF.allows(ProgressKind.STATUS, StatusTag.ENGINE_TURN_START))
                .isTrue();
        assertThat(ProgressLevel.OFF.allows(ProgressKind.STATUS, StatusTag.ENGINE_TURN_END))
                .isTrue();
    }

    @Test
    void statusAsides_areSilentAtOff() {
        assertThat(ProgressLevel.OFF.allows(ProgressKind.STATUS, StatusTag.INFO))
                .isFalse();
        assertThat(ProgressLevel.OFF.allows(ProgressKind.STATUS, StatusTag.TOOL_START))
                .isFalse();
    }

    @Test
    void normal_silencesOnlyInfoAsides() {
        assertThat(ProgressLevel.NORMAL.allows(ProgressKind.STATUS, StatusTag.INFO))
                .isFalse();
        assertThat(ProgressLevel.NORMAL.allows(ProgressKind.STATUS, StatusTag.TOOL_START))
                .isTrue();
        assertThat(ProgressLevel.NORMAL.allows(ProgressKind.STATUS, StatusTag.ENGINE_TURN_START))
                .isTrue();
    }

    @Test
    void verbose_passesEverything() {
        assertThat(ProgressLevel.VERBOSE.allows(ProgressKind.STATUS, StatusTag.INFO))
                .isTrue();
    }

    @Test
    void planAndReply_areStructuralAtEveryLevel() {
        for (ProgressLevel level : ProgressLevel.values()) {
            assertThat(level.allows(ProgressKind.PLAN, null)).isTrue();
            assertThat(level.allows(ProgressKind.REPLY, null)).isTrue();
        }
    }
}
