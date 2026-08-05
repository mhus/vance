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
class ThinkingCommandHandlerTest {

    @Mock private ThinkProcessService thinkProcessService;

    private ThinkingCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ThinkingCommandHandler(thinkProcessService);
        when(thinkProcessService.setEngineParamOverride(any(), any(), any())).thenReturn(true);
    }

    private ThinkProcessDocument process() {
        return ThinkProcessDocument.builder().id("p1").build();
    }

    private EngineCommand cmd(String text) {
        return new EngineCommand("thinking", Map.of("text", text));
    }

    @Test
    void level_setsNormalisedOverride() {
        EngineCommandResult result = handler.handle(process(), cmd("High"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride("p1", "thinking", "high");
    }

    @Test
    void off_setsOffOverride() {
        EngineCommandResult result = handler.handle(process(), cmd("off"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride("p1", "thinking", "off");
    }

    @Test
    void invalidLevel_isErrorAndDoesNotWrite() {
        EngineCommandResult result = handler.handle(process(), cmd("normal"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("normal");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void clear_unsetsOverride() {
        EngineCommandResult result = handler.handle(process(), cmd("clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride(eq("p1"), eq("thinking"), isNull());
    }

    @Test
    void get_reportsStateWithoutWriting() {
        ThinkProcessDocument process = process();
        process.setEngineParamOverrides(new java.util.HashMap<>(Map.of("thinking", "medium")));

        EngineCommandResult result = handler.handle(process, cmd("get"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("medium").contains("override");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void empty_reportsRecipeDefaultState() {
        EngineCommandResult result = handler.handle(process(), cmd(""));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("off").contains("recipe default");
        verifyNoInteractions(thinkProcessService);
    }
}
