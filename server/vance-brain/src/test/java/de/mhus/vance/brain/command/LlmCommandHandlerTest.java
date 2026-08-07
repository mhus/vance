package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmCommandHandlerTest {

    @Mock private ThinkProcessService thinkProcessService;

    private LlmCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LlmCommandHandler(thinkProcessService);
        when(thinkProcessService.setEngineParamOverride(any(), any(), any())).thenReturn(true);
    }

    private ThinkProcessDocument process() {
        return ThinkProcessDocument.builder().id("p1").build();
    }

    private EngineCommand cmd(String text) {
        return new EngineCommand("llm", Map.of("text", text));
    }

    @Test
    void temperature_setsTypedDouble() {
        EngineCommandResult result = handler.handle(process(), cmd("temperature 0.2"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride("p1", "temperature", 0.2);
    }

    @Test
    void topK_setsTypedInteger() {
        EngineCommandResult result = handler.handle(process(), cmd("topK 40"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride("p1", "topK", 40);
    }

    @Test
    void key_isCaseInsensitiveAndCanonicalised() {
        EngineCommandResult result = handler.handle(process(), cmd("TOPP 0.9"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride("p1", "topP", 0.9);
    }

    @Test
    void nonNumericValue_isErrorAndDoesNotWrite() {
        EngineCommandResult result = handler.handle(process(), cmd("temperature hot"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void unknownKey_isErrorWithHint() {
        EngineCommandResult result = handler.handle(process(), cmd("model gpt-5"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("temperature");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void keyWithoutValue_isError() {
        EngineCommandResult result = handler.handle(process(), cmd("temperature"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void clear_unsetsSingleKey() {
        EngineCommandResult result = handler.handle(process(), cmd("temperature clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride(eq("p1"), eq("temperature"), isNull());
    }

    @Test
    void bareClear_isErrorAskingForKey() {
        EngineCommandResult result = handler.handle(process(), cmd("clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void get_listsActiveOverrides() {
        ThinkProcessDocument process = process();
        process.setEngineParamOverrides(new HashMap<>(Map.of("temperature", 0.2, "topK", 40)));

        EngineCommandResult result = handler.handle(process, cmd("get"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("temperature=0.2").contains("topK=40");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void empty_reportsNoOverrides() {
        EngineCommandResult result = handler.handle(process(), cmd(""));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("no llm overrides");
        verifyNoInteractions(thinkProcessService);
    }

    // ─── Range validation ────────────────────────────────────────────
    // The override applies from the *next* turn, so a provider-side 400
    // would surface far away from the command that caused it. Rejecting
    // here keeps a typo from costing a whole turn.

    @Test
    void aboveMaximum_isRejectedBeforeItIsStored() {
        EngineCommandResult result = handler.handle(process(), cmd("topP 5"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("topP").contains("<= 1");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void belowMinimum_isRejectedBeforeItIsStored() {
        EngineCommandResult result = handler.handle(process(), cmd("temperature -1"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("temperature").contains(">= 0");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void absurdMaxTokens_isRejected() {
        EngineCommandResult result = handler.handle(process(), cmd("maxTokens 99999999"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("maxTokens");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void boundaryValues_areAccepted() {
        assertThat(handler.handle(process(), cmd("temperature 0")).outcome())
                .isEqualTo(EngineCommandOutcome.OK);
        assertThat(handler.handle(process(), cmd("temperature 2")).outcome())
                .isEqualTo(EngineCommandOutcome.OK);
        assertThat(handler.handle(process(), cmd("presencePenalty -2")).outcome())
                .isEqualTo(EngineCommandOutcome.OK);
    }

    @Test
    void seed_hasNoRangeAndTakesAnyLong() {
        EngineCommandResult result = handler.handle(process(), cmd("seed -9999999999"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride("p1", "seed", -9999999999L);
    }
}
