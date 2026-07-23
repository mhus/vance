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

    private ThinkProcessDocument processWith(StrategyState state, String yaml) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(VogonEngine.PLAN_KEY, yaml);
        params.put(VogonEngine.STATE_KEY, objectMapper.convertValue(state, Map.class));
        ThinkProcessDocument p = ThinkProcessDocument.builder()
                .tenantId("acme").sessionId("s-1").name("vg-1")
                .parentProcessId("parent-1")
                .engineParams(params)
                .build();
        p.setId("vg-1");
        return p;
    }

    private static final String YAML_WITH_CHECKPOINT_ONFAILURE =
            "name: t\n"
            + "phases:\n"
            + "  - name: gate1\n"
            + "    checkpoint:\n"
            + "      type: approval\n"
            + "      message: \"approve?\"\n"
            + "result:\n"
            + "  text: \"all good\"\n"
            + "  onFailure:\n"
            + "    text: \"it failed\"\n";

    @Test
    void completedStrategy_emitsResultBlockReply_andClosesDone() {
        StrategyState state = StrategyState.builder()
                .strategy("t").strategyComplete(true).build();
        ThinkProcessDocument process = processWith(state, YAML_WITH_RESULT);
        ThinkEngineContext ctx = mock(ThinkEngineContext.class);
        when(ctx.drainPending()).thenReturn(List.of());

        engine.runTurn(process, ctx);

        // The result: block text reaches the parent, and the process closes DONE.
        verify(ctx).emitReply(eq("all good"), isNull(), any());
        verify(thinkProcessService).closeProcess("vg-1", CloseReason.DONE);
    }

    @Test
    void failedCheckpointClosePath_emitsOnFailureBlock_andClosesStale() {
        // A checkpoint answered as failed (rejected approval /
        // undecidable — HIGH #1/#2) must close STALE via emitFinalReply so
        // the onFailure block reaches the parent. Before HIGH #3 this
        // failure close skipped the result block and onFailure was dead
        // code (code-review Phase 2).
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("gate1_checkpointAnswered", true);
        flags.put("gate1_failed", true);
        StrategyState state = StrategyState.builder()
                .strategy("t")
                .currentPhasePath(new java.util.ArrayList<>(List.of("gate1")))
                .flags(flags)
                .build();
        ThinkProcessDocument process = processWith(state, YAML_WITH_CHECKPOINT_ONFAILURE);
        ThinkEngineContext ctx = mock(ThinkEngineContext.class);
        when(ctx.drainPending()).thenReturn(List.of());

        engine.runTurn(process, ctx);

        verify(ctx).emitReply(eq("it failed"), isNull(), any());
        verify(thinkProcessService).closeProcess("vg-1", CloseReason.STALE);
    }
}
