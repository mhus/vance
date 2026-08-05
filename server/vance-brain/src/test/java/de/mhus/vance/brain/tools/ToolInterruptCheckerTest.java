package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import org.junit.jupiter.api.Test;

class ToolInterruptCheckerTest {

    private final ThinkProcessService svc = mock(ThinkProcessService.class);
    private final ToolInterruptChecker checker = new ToolInterruptChecker(svc);

    @Test
    void nullOrBlankProcessId_neverHalted() {
        assertThat(checker.isHalted(null)).isFalse();
        assertThat(checker.isHalted("  ")).isFalse();
        assertThatCode(() -> checker.throwIfHalted(null)).doesNotThrowAnyException();
    }

    @Test
    void haltFlagSet_throws() {
        when(svc.isHaltRequested("p1")).thenReturn(true);

        assertThat(checker.isHalted("p1")).isTrue();
        assertThatThrownBy(() -> checker.throwIfHalted("p1"))
                .isInstanceOf(ToolInterruptedException.class);
    }

    @Test
    void haltFlagClear_noop() {
        when(svc.isHaltRequested("p1")).thenReturn(false);

        assertThat(checker.isHalted("p1")).isFalse();
        assertThatCode(() -> checker.throwIfHalted("p1")).doesNotThrowAnyException();
    }
}
