package de.mhus.vance.addon.brain.nutrimat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code //nutrimat} control-plane parsing: the knob mapping onto the runtime
 * override, the clear semantics, and the status shape. The service write is
 * mocked — the handler is pure glue plus formatting.
 */
class NutrimatCommandHandlerTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);

    /** Lazy provider like in production — the handler never touches the natures eagerly. */
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AbstractNutrimat> provider = mock(ObjectProvider.class);

    private final NutrimatCommandHandler handler = new NutrimatCommandHandler(thinkProcessService, provider);

    @BeforeEach
    void setUp() {
        when(provider.stream()).thenAnswer(inv -> Stream.of(new FakeNature()));
    }

    /** Minimal budget nature so the status and set knobs have something to read. */
    private static class FakeNature extends AbstractNutrimat {
        FakeNature() {
            super(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        @Override
        protected String natureId() {
            return "janx";
        }

        @Override
        protected String loopType() {
            return "natural-stop tool loop (the Ford baseline)";
        }

        @Override
        protected TurnOutcome runLoop(
                ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
            return TurnOutcome.terminal("unused — these tests never run a loop", false);
        }

        @Override
        protected int iterationBudget(ThinkProcessDocument process) {
            return paramInt(process, "maxIterations", 40);
        }
    }

    /** A nature without a budget — the loop runs until the natural stop. */
    private static class CaplessNature extends FakeNature {
        @Override
        protected String natureId() {
            return "salitos";
        }

        @Override
        protected int iterationBudget(ThinkProcessDocument process) {
            return 0;
        }
    }

    private static ThinkProcessDocument nutrimatProcess() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("p-1");
        process.setThinkEngine("nutrimat-janx");
        process.setName("worker-1");
        process.setEngineParams(Map.of("maxIterations", 12));
        return process;
    }

    private static EngineCommand command(String text) {
        return new EngineCommand("nutrimat", text.isEmpty() ? Map.of() : Map.of("text", text));
    }

    @Test
    void setMaxturns_writesTheRuntimeOverride() {
        when(thinkProcessService.setEngineParamOverride(anyString(), anyString(), any()))
                .thenReturn(true);

        EngineCommandResult result = handler.handle(nutrimatProcess(), command("set maxturns 30"));

        assertThat(result.message()).contains("maxIterations = 30");
        verify(thinkProcessService).setEngineParamOverride(anyString(), eq("maxIterations"), eq(30));
    }

    @Test
    void setWithoutValue_clearsTheOverride() {
        when(thinkProcessService.setEngineParamOverride(anyString(), anyString(), isNull()))
                .thenReturn(true);

        EngineCommandResult result = handler.handle(nutrimatProcess(), command("set maxturns"));

        assertThat(result.message()).contains("override cleared");
        verify(thinkProcessService).setEngineParamOverride(anyString(), eq("maxIterations"), isNull());
    }

    @Test
    void setRejectsUnknownKnobsAndBadNumbers() {
        assertThat(handler.handle(nutrimatProcess(), command("set frobnicate 1"))
                        .message())
                .contains("Unknown knob");
        assertThat(handler.handle(nutrimatProcess(), command("set maxturns abc"))
                        .message())
                .contains("must be a number");
        assertThat(handler.handle(nutrimatProcess(), command("set maxturns 0")).message())
                .contains(">= 1");
    }

    @Test
    void status_reportsEffectiveBudgetWithItsSource() {
        EngineCommandResult result = handler.handle(nutrimatProcess(), command("status"));

        assertThat(result.message())
                .contains("nutrimat-janx")
                .contains("maxIterations=12 (recipe)")
                .contains("natural-stop tool loop");
    }

    @Test
    void status_honoursTheRuntimeOverride() {
        ThinkProcessDocument process = nutrimatProcess();
        process.setEngineParamOverrides(Map.of("maxIterations", 30));

        EngineCommandResult result = handler.handle(process, command(""));

        assertThat(result.message()).contains("maxIterations=30 (runtime override)");
    }

    @Test
    void handle_rejectsNonNutrimatProcesses() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setThinkEngine("ford");

        EngineCommandResult result = handler.handle(process, command("status"));

        assertThat(result.message()).contains("only in a Nutrimat process");
    }

    @Test
    void caplessNature_statusShowsNoIterationCap() {
        when(provider.stream()).thenAnswer(inv -> Stream.of(new CaplessNature()));
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("p-2");
        process.setThinkEngine("nutrimat-salitos");

        EngineCommandResult result = handler.handle(process, command("status"));

        // No budget nature: its own loop decides what bounds it — and
        // maxturns makes no sense.
        assertThat(result.message()).contains("no round budget");
    }

    @Test
    void caplessNature_setMaxturnsIsRejected() {
        when(provider.stream()).thenAnswer(inv -> Stream.of(new CaplessNature()));
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("p-3");
        process.setThinkEngine("nutrimat-salitos");

        EngineCommandResult result = handler.handle(process, command("set maxturns 30"));

        assertThat(result.message()).contains("no iteration budget");
        verify(thinkProcessService, never()).setEngineParamOverride(anyString(), anyString(), any());
    }
}
