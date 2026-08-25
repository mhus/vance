package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The one thing this engine adds to Frankie: a worker that asked a
 * question survives its own turn.
 *
 * <p>Both exits use Frankie's {@code _terminate} — the loop stops either
 * way. What differs is the consequence, and getting that wrong is not a
 * cosmetic bug: a worker wrongly closed cannot be handed the answer it
 * asked for, and everything it established is gone.
 */
class TrillianWorkerEngineTest {

    private static final String PROC = "worker-1";

    private final ThinkProcessService processes = mock(ThinkProcessService.class);

    @Test
    void aWorkerThatAsked_parksInsteadOfClosing() {
        givenAskPending(true);

        ThinkProcessStatus status = engine().onWorkerTerminate(process());

        assertThat(status).isEqualTo(ThinkProcessStatus.IDLE);
        verify(processes, never()).closeProcess(any(), any());
    }

    @Test
    void theMarkerSurvivesThePark_soAParkedWorkerStaysDistinguishable() {
        // Clearing it here would collapse "waiting for an answer" and
        // "simply finished" into the same IDLE, and a Nature reading the
        // obstacle markers would then advise a nudge for a worker that has
        // nothing left to be nudged about.
        givenAskPending(true);

        engine().onWorkerTerminate(process());

        verify(processes, never()).setEngineParamOverride(
                PROC, TrillianWorkerEngine.PARAM_ASK_PENDING, null);
    }

    @Test
    void theMarkerIsClearedWhenTheWorkerRunsAgain() {
        // The worker running again IS the answer arriving — otherwise a
        // later real termination would park instead of close.
        givenAskPending(true);

        try {
            // Only the pre-loop side effect is under test; Frankie's turn
            // itself runs against unconfigured collaborators.
            engine().runTurn(process(), mock(
                    de.mhus.vance.brain.thinkengine.ThinkEngineContext.class));
        } catch (RuntimeException expected) {
            // ignored on purpose
        }

        verify(processes).setEngineParamOverride(
                PROC, TrillianWorkerEngine.PARAM_ASK_PENDING, null);
    }

    @Test
    void aWorkerThatFinished_closesAsFrankieWould() {
        givenAskPending(false);

        ThinkProcessStatus status = engine().onWorkerTerminate(process());

        assertThat(status).isNull();
        verify(processes).closeProcess(PROC, CloseReason.DONE);
    }

    @Test
    void anUnreadableMarker_closes() {
        // Closing is the safe reading of an unclear state: a worker
        // wrongly kept alive waits forever, one wrongly closed costs a
        // re-spawn.
        when(processes.findById(PROC)).thenThrow(new IllegalStateException("mongo down"));

        assertThat(engine().onWorkerTerminate(process())).isNull();
        verify(processes).closeProcess(PROC, CloseReason.DONE);
    }

    @Test
    void itIsItsOwnEngine_notFrankie() {
        // The recipes address it by name; sharing Frankie's would make
        // the two indistinguishable to the registry.
        assertThat(engine().name()).isEqualTo("trillian-worker");
    }

    private void givenAskPending(boolean pending) {
        ThinkProcessDocument doc = process();
        if (pending) {
            doc.setEngineParamOverrides(new LinkedHashMap<>(
                    Map.of(TrillianWorkerEngine.PARAM_ASK_PENDING, true)));
        }
        when(processes.findById(PROC)).thenReturn(Optional.of(doc));
    }

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(PROC);
        p.setEngineParamOverrides(new LinkedHashMap<>());
        return p;
    }

    /** Only the seam is exercised, so the 19 unused collaborators are mocks. */
    private TrillianWorkerEngine engine() {
        return new TrillianWorkerEngine(
                processes, mock(de.mhus.vance.brain.frankie.FrankieProperties.class),
                mock(de.mhus.vance.brain.ai.EngineChatFactory.class),
                mock(de.mhus.vance.brain.progress.LlmCallTracker.class),
                mock(de.mhus.vance.brain.events.StreamingProperties.class),
                mock(tools.jackson.databind.ObjectMapper.class),
                mock(de.mhus.vance.brain.thinkengine.EnginePromptResolver.class),
                mock(de.mhus.vance.brain.thinkengine.SystemPromptComposer.class),
                mock(de.mhus.vance.brain.skill.SkillResolver.class),
                mock(de.mhus.vance.brain.skill.SkillPromptComposer.class),
                mock(de.mhus.vance.shared.session.SessionService.class),
                mock(de.mhus.vance.brain.context.PromptDateContextResolver.class),
                mock(de.mhus.vance.brain.prompt.ScratchpadPromptContributor.class),
                mock(de.mhus.vance.brain.memory.MemoryContextLoader.class),
                mock(de.mhus.vance.brain.ai.ModelCatalog.class),
                mock(de.mhus.vance.brain.memory.MemoryCompactionService.class),
                mock(de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry.class),
                mock(de.mhus.vance.brain.guard.CompletionGuardService.class),
                mock(de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer.class),
                mock(de.mhus.vance.brain.prompt.ClientTurnContextResolver.class));
    }
}
