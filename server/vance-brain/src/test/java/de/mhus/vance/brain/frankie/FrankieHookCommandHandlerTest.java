package de.mhus.vance.brain.frankie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FrankieHookCommandHandlerTest {

    @Mock private ThinkProcessService thinkProcessService;
    @Mock private FrankiePostCompletionHookHandler hookHandler;

    private FrankieHookCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FrankieHookCommandHandler(thinkProcessService, hookHandler);
    }

    private ThinkProcessDocument frankie() {
        return ThinkProcessDocument.builder().id("p1").thinkEngine(FrankieEngine.NAME).build();
    }

    private EngineCommand cmd(String text) {
        return new EngineCommand("frankie.hook",
                text == null ? Map.of() : Map.of("text", text));
    }

    @Test
    void onOtherEngine_reportsUnknown() {
        ThinkProcessDocument arthur =
                ThinkProcessDocument.builder().id("p1").thinkEngine("arthur").build();

        EngineCommandResult result = handler.handle(arthur, cmd("get"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.UNKNOWN);
    }

    @Test
    void get_returnsEffectiveTemplateAsValue() {
        ThinkProcessDocument p = frankie();
        when(hookHandler.resolveEffectiveGoalTemplate(p)).thenReturn("EFFECTIVE TPL");
        when(hookHandler.hasPostCompletionHook(p)).thenReturn(true);

        EngineCommandResult result = handler.handle(p, cmd(null));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.value()).isEqualTo("EFFECTIVE TPL");
    }

    @Test
    void set_persistsOverride() {
        ThinkProcessDocument p = frankie();
        when(thinkProcessService.setPostCompletionHookGoalOverride(eq("p1"), eq("do a review")))
                .thenReturn(true);
        when(hookHandler.hasPostCompletionHook(p)).thenReturn(true);

        EngineCommandResult result = handler.handle(p, cmd("set do a review"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setPostCompletionHookGoalOverride("p1", "do a review");
    }

    @Test
    void set_withoutTemplate_isError() {
        EngineCommandResult result = handler.handle(frankie(), cmd("set"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }

    @Test
    void clear_unsetsOverride() {
        ThinkProcessDocument p = frankie();
        when(thinkProcessService.setPostCompletionHookGoalOverride(eq("p1"), isNull()))
                .thenReturn(true);

        EngineCommandResult result = handler.handle(p, cmd("clear"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        verify(thinkProcessService).setPostCompletionHookGoalOverride("p1", null);
    }
}
