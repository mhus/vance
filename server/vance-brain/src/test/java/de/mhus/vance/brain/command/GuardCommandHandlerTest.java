package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.brain.guard.CompletionGuardService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
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
class GuardCommandHandlerTest {

    @Mock private ThinkProcessService thinkProcessService;
    @Mock private CompletionGuardService guardService;

    private GuardCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GuardCommandHandler(thinkProcessService, guardService);
        when(thinkProcessService.setGuardOverride(any(), any(), any())).thenReturn(true);
        when(guardService.resolveGuards(any())).thenReturn(List.of());
    }

    private ThinkProcessDocument process() {
        return ThinkProcessDocument.builder().id("p1").build();
    }

    private EngineCommand cmd(String text) {
        return new EngineCommand("guard", Map.of("text", text));
    }

    @Test
    void judge_setsJudgeOverride() {
        EngineCommandResult result = handler.handle(process(), cmd("judge Is the task done?"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setGuardOverride("p1", "guardJudgeOverride", "Is the task done?");
    }

    @Test
    void prompt_setsPromptOverride() {
        EngineCommandResult result = handler.handle(process(), cmd("prompt Did you build?"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setGuardOverride("p1", "guardPromptOverride", "Did you build?");
    }

    @Test
    void judge_withoutText_isError() {
        EngineCommandResult result = handler.handle(process(), cmd("judge"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }

    @Test
    void clear_unsetsBothFields() {
        EngineCommandResult result = handler.handle(process(), cmd("clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setGuardOverride(eq("p1"), eq("guardJudgeOverride"), isNull());
        verify(thinkProcessService).setGuardOverride(eq("p1"), eq("guardPromptOverride"), isNull());
    }

    @Test
    void get_reportsState() {
        EngineCommandResult result = handler.handle(process(), cmd("get"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
    }

    @Test
    void unknownSubcommand_isError() {
        EngineCommandResult result = handler.handle(process(), cmd("frobnicate"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }
}
