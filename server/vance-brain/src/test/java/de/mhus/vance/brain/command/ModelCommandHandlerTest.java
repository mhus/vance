package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chatmodel.ChatModelStateDto;
import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.brain.ai.ChatModelService;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelCommandHandlerTest {

    @Mock
    private ThinkProcessService thinkProcessService;

    @Mock
    private ChatModelService chatModelService;

    private ModelCommandHandler handler;
    private final ThinkProcessDocument process =
            ThinkProcessDocument.builder().id("p1").build();
    private final ChatModelStateDto state = ChatModelStateDto.builder()
            .model("cortecs:glm-5")
            .modelOverridden(true)
            .thinking("high")
            .modelSupportsThinking(true)
            .build();

    @BeforeEach
    void setUp() {
        handler = new ModelCommandHandler(thinkProcessService, chatModelService);
        when(thinkProcessService.setEngineParamOverride(any(), any(), any())).thenReturn(true);
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        when(chatModelService.stateOf(any())).thenReturn(state);
    }

    private static EngineCommand cmd(String text) {
        return new EngineCommand("model", text.isEmpty() ? Map.of() : Map.of("text", text));
    }

    @Test
    void runsOffTheLane() {
        assertThat(handler.runsOnLane()).isFalse();
    }

    @Test
    void set_validatedSpec_storesResolvedSpec() {
        when(chatModelService.validate(process, "default:fast"))
                .thenReturn(new ChatModelService.Selection("cortecs:glm-5", mock(ModelInfo.class)));

        EngineCommandResult result = handler.handle(process, cmd("default:fast"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("cortecs:glm-5");
        assertThat(result.value()).isSameAs(state);
        verify(thinkProcessService).setEngineParamOverride("p1", "model", "cortecs:glm-5");
    }

    @Test
    void set_refusedModel_isErrorAndWritesNothing() {
        when(chatModelService.validate(process, "gemini:x"))
                .thenThrow(new ChatModelService.ModelNotSelectableException("not approved by setting"));

        EngineCommandResult result = handler.handle(process, cmd("gemini:x"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("not approved");
        verify(thinkProcessService, never()).setEngineParamOverride(anyString(), anyString(), any());
    }

    @Test
    void clear_removesOverride() {
        EngineCommandResult result = handler.handle(process, cmd("clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setEngineParamOverride(eq("p1"), eq("model"), isNull());
    }

    @Test
    void get_andEmpty_returnStateWithoutWriting() {
        EngineCommandResult get = handler.handle(process, cmd("get"));
        EngineCommandResult empty = handler.handle(process, cmd(""));

        assertThat(get.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(get.value()).isSameAs(state);
        assertThat(get.message())
                .contains("cortecs:glm-5")
                .contains("runtime override")
                .contains("thinking: high");
        assertThat(empty.value()).isSameAs(state);
        verify(thinkProcessService, never()).setEngineParamOverride(anyString(), anyString(), any());
    }

    @Test
    void set_processGone_isError() {
        when(chatModelService.validate(process, "cortecs:glm-5"))
                .thenReturn(new ChatModelService.Selection("cortecs:glm-5", mock(ModelInfo.class)));
        when(thinkProcessService.setEngineParamOverride(any(), any(), any())).thenReturn(false);

        EngineCommandResult result = handler.handle(process, cmd("cortecs:glm-5"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }
}
