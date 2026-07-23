package de.mhus.vance.brain.vogon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.vogon.StrategyState;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Engine-turn-level harness for the Vogon strategy runner, focused on the
 * final-reply / close behaviour that {@code result:}/{@code onFailure:}
 * delivery depends on (code-review Phase 2). Drives {@code runTurn}
 * against a snapshot strategy supplied inline via {@code engineParams}, so
 * no live strategy document is needed.
 */
class VogonEngineCloseReplyTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final StrategyResolver strategyResolver = mock(StrategyResolver.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);
    private final InboxItemService inboxItemService = mock(InboxItemService.class);
    private final RecipeResolver recipeResolver = mock(RecipeResolver.class);
    private final ProcessEventEmitter eventEmitter = mock(ProcessEventEmitter.class);
    private final LaneScheduler laneScheduler = mock(LaneScheduler.class);
    private final ProgressEmitter progressEmitter = mock(ProgressEmitter.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ThinkEngineService> engineProvider = mock(ObjectProvider.class);

    private final VogonEngine engine = new VogonEngine(
            strategyResolver, documentService, sessionService, thinkProcessService,
            chatMessageService, inboxItemService, recipeResolver, eventEmitter,
            laneScheduler, objectMapper, progressEmitter, engineProvider);

    private static final String YAML_WITH_RESULT =
            "name: t\n"
            + "phases:\n"
            + "  - name: noop\n"
            + "    type: gate\n"
            + "    gate: { requires: [done] }\n"
            + "result:\n"
            + "  text: \"all good\"\n";

    private ThinkProcessDocument processWith(StrategyState state) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(VogonEngine.PLAN_KEY, YAML_WITH_RESULT);
        params.put(VogonEngine.STATE_KEY, objectMapper.convertValue(state, Map.class));
        ThinkProcessDocument p = ThinkProcessDocument.builder()
                .tenantId("acme").sessionId("s-1").name("vg-1")
                .parentProcessId("parent-1")
                .engineParams(params)
                .build();
        p.setId("vg-1");
        return p;
    }

    @Test
    void completedStrategy_emitsResultBlockReply_andClosesDone() {
        StrategyState state = StrategyState.builder()
                .strategy("t").strategyComplete(true).build();
        ThinkProcessDocument process = processWith(state);
        ThinkEngineContext ctx = mock(ThinkEngineContext.class);
        when(ctx.drainPending()).thenReturn(List.of());

        engine.runTurn(process, ctx);

        // The result: block text reaches the parent, and the process closes DONE.
        verify(ctx).emitReply(eq("all good"), isNull(), any());
        verify(thinkProcessService).closeProcess("vg-1", CloseReason.DONE);
    }
}
