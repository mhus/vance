package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

    private ThinkProcessDocument sessionProcess() {
        return ThinkProcessDocument.builder().id("p1").sessionId("s1").build();
    }

    private EngineCommand cmd(String text) {
        return new EngineCommand("guard", Map.of("text", text));
    }

    @Test
    void script_setsScriptOverride() {
        EngineCommandResult result = handler.handle(
                process(), cmd("script _vance/guards/dev-done.js"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setGuardOverride("p1", "_vance/guards/dev-done.js", null);
    }

    @Test
    void script_withoutPath_isError() {
        EngineCommandResult result = handler.handle(process(), cmd("script"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }

    @Test
    void inline_setsBodyOverride() {
        EngineCommandResult result = handler.handle(
                process(), cmd("inline vance.process.notify('Hello World!');"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setGuardOverride(
                "p1", null, "vance.process.notify('Hello World!');");
    }

    @Test
    void inline_withoutBody_isError() {
        EngineCommandResult result = handler.handle(process(), cmd("inline"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }

    @Test
    void clear_unsetsBothOverrides() {
        EngineCommandResult result = handler.handle(process(), cmd("clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setGuardOverride(eq("p1"), isNull(), isNull());
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

    @Test
    void status_show_reportsScopes() {
        when(guardService.loopScratchView(any())).thenReturn(Map.of("asked", true));
        when(guardService.sessionScratchView(any())).thenReturn(Map.of());

        EngineCommandResult result = handler.handle(sessionProcess(), cmd("status"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("loop: 1 entries");
    }

    @Test
    void status_set_writesLoopScratch() {
        EngineCommandResult result = handler.handle(process(), cmd("status set asked true"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(guardService).putScratch(any(), eq(false), eq("asked"), eq("true"));
    }

    @Test
    void status_sessionSet_writesSessionScratch() {
        EngineCommandResult result = handler.handle(sessionProcess(), cmd("status session set k v"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(guardService).putScratch(any(), eq(true), eq("k"), eq("v"));
    }

    @Test
    void status_setWithoutValue_isError() {
        EngineCommandResult result = handler.handle(process(), cmd("status set onlykey"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        verify(guardService, org.mockito.Mockito.never()).putScratch(any(), anyBoolean(), any(), any());
    }

    @Test
    void status_del_removesLoopKey() {
        when(guardService.removeScratch(any(), eq(false), eq("asked"))).thenReturn(true);

        EngineCommandResult result = handler.handle(process(), cmd("status del asked"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(guardService).removeScratch(any(), eq(false), eq("asked"));
    }

    @Test
    void status_clear_clearsLoopScratch() {
        EngineCommandResult result = handler.handle(process(), cmd("status clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(guardService).clearScratch(any(), eq(false));
    }

    @Test
    void status_sessionOp_withoutSession_isError() {
        EngineCommandResult result = handler.handle(process(), cmd("status session set k v"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        verify(guardService, org.mockito.Mockito.never()).putScratch(any(), anyBoolean(), any(), any());
    }
}
