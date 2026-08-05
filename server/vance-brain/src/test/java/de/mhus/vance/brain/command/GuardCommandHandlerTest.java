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
        when(thinkProcessService.setGuardScriptOverride(any(), any())).thenReturn(true);
        when(guardService.resolveGuards(any())).thenReturn(List.of());
    }

    private ThinkProcessDocument process() {
        return ThinkProcessDocument.builder().id("p1").build();
    }

    private EngineCommand cmd(String text) {
        return new EngineCommand("guard", Map.of("text", text));
    }

    @Test
    void script_setsScriptOverride() {
        EngineCommandResult result = handler.handle(
                process(), cmd("script _vance/guards/dev-done.js"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setGuardScriptOverride("p1", "_vance/guards/dev-done.js");
    }

    @Test
    void script_withoutPath_isError() {
        EngineCommandResult result = handler.handle(process(), cmd("script"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }

    @Test
    void clear_unsetsScript() {
        EngineCommandResult result = handler.handle(process(), cmd("clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setGuardScriptOverride(eq("p1"), isNull());
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
