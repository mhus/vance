package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.foot.connection.BrainException;
import org.junit.jupiter.api.Test;

/**
 * Only a {@code 409} that says "the project moved" is retried — a session held
 * by a sibling connection needs the user's consent, and a plain conflict is an
 * answer.
 */
class SessionBootstrapCommandRetryTest {

    @Test
    void heldElsewhere_isRetried() {
        assertThat(SessionBootstrapCommand.isHeldElsewhere(
                        new BrainException(409, "moved", ErrorData.REASON_PROJECT_HELD_ELSEWHERE)))
                .isTrue();
    }

    @Test
    void otherConflicts_areNot() {
        assertThat(SessionBootstrapCommand.isHeldElsewhere(
                        new BrainException(409, "bound", ErrorData.REASON_SESSION_BOUND_ELSEWHERE)))
                .isFalse();
        assertThat(SessionBootstrapCommand.isHeldElsewhere(new BrainException(409, "homeless")))
                .isFalse();
        assertThat(SessionBootstrapCommand.isHeldElsewhere(
                        new BrainException(500, "boom", ErrorData.REASON_PROJECT_HELD_ELSEWHERE)))
                .isFalse();
    }
}
