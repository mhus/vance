package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandDispatcher;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Sequence semantics of the skill command runner: best-effort across
 * regular handler errors, but a Shooty guard denial aborts the remaining
 * sequence hard — continuing past a fail-closed veto would let a
 * half-vetoed skill sequence run to completion as if nothing happened.
 * See {@code planning/shooty.md} §4.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillCommandRunnerTest {

    @Mock
    private EngineCommandDispatcher dispatcher;

    private SkillCommandRunner runner;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        runner = new SkillCommandRunner(dispatcher);
        process = ThinkProcessDocument.builder().id("p1").build();
        when(dispatcher.dispatch(any(), any())).thenReturn(EngineCommandResult.ok());
    }

    @Test
    void regularError_isBestEffort_sequenceContinues() {
        EngineCommand first = new EngineCommand("a", java.util.Map.of());
        EngineCommand second = new EngineCommand("b", java.util.Map.of());
        when(dispatcher.dispatch(any(), any()))
                .thenReturn(EngineCommandResult.error("handler failed"), EngineCommandResult.ok());

        List<EngineCommandResult> results = runner.run(process, List.of(first, second), "activate", "sk");

        assertThat(results).hasSize(2);
        verify(dispatcher).dispatch(process, second);
    }

    @Test
    void guardDenial_abortsRemainingSequence() {
        EngineCommand first = new EngineCommand("a", java.util.Map.of());
        EngineCommand second = new EngineCommand("b", java.util.Map.of());
        EngineCommand third = new EngineCommand("c", java.util.Map.of());
        when(dispatcher.dispatch(any(), any())).thenReturn(EngineCommandResult.guardDenied("Guard denied: unsafe"));

        List<EngineCommandResult> results = runner.run(process, List.of(first, second, third), "activate", "sk");

        // The denied command is the last recorded result — nothing after it ran.
        assertThat(results).hasSize(1);
        assertThat(results.get(0).deniedByGuard()).isTrue();
        assertThat(results.get(0).outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        verify(dispatcher, never()).dispatch(process, second);
        verify(dispatcher, never()).dispatch(process, third);
    }

    @Test
    void guardDenial_abortsDeactivateCleanupToo() {
        // Even a deactivate cleanup sequence stops: a guard denial is a
        // deliberate veto, not the transient error the best-effort rule
        // exists for (partially-applied deactivate cleanup).
        EngineCommand first = new EngineCommand("a", java.util.Map.of());
        EngineCommand second = new EngineCommand("b", java.util.Map.of());
        when(dispatcher.dispatch(any(), any())).thenReturn(EngineCommandResult.guardDenied("Guard denied: unsafe"));

        List<EngineCommandResult> results = runner.run(process, List.of(first, second), "deactivate", "sk");

        assertThat(results).hasSize(1);
        verify(dispatcher, never()).dispatch(process, second);
    }
}
