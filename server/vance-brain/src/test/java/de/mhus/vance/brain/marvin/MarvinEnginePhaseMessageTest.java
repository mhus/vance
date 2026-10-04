package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.marvin.WorkerPhase;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Regression test for the phase-message assembly.
 *
 * <p>A phase turn is {@code [system, user]} with NO chat history, so the
 * only place a worker can see its CALL_RECIPE results is
 * {@code buildPhaseUserMessage}. The replies were persisted on the node by
 * {@code appendCallReply} but never rendered back into that message: the
 * REFLECT instruction said "The latest CALL_RECIPE result is above" while
 * nothing was above, and the worker concluded and validated blind. Full
 * sub-process answers sat unread in {@code artifacts.recipeReplies} while
 * the node looped CONCLUDE/VALIDATE and re-called the recipe — visible to
 * the user as repeated spawn calls whose results never appeared.
 */
class MarvinEnginePhaseMessageTest {

    @SuppressWarnings("unchecked")
    private final MarvinEngine engine = new MarvinEngine(
            mock(MarvinNodeService.class),
            new MarvinProperties(),
            mock(de.mhus.vance.shared.inbox.MaximegalonService.class),
            mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
            mock(de.mhus.vance.shared.chat.ChatMessageService.class),
            mock(de.mhus.vance.brain.recipe.RecipeResolver.class),
            mock(de.mhus.vance.brain.recipe.RecipeLoader.class),
            mock(PhaseOutputParser.class),
            mock(PlanSnapshotRenderer.class),
            mock(de.mhus.vance.brain.progress.LlmCallTracker.class),
            mock(de.mhus.vance.brain.progress.ProgressEmitter.class),
            mock(de.mhus.vance.brain.thinkengine.EnginePromptResolver.class),
            mock(de.mhus.vance.brain.thinkengine.SystemPromptComposer.class),
            mock(de.mhus.vance.brain.ai.EngineChatFactory.class),
            mock(ObjectMapper.class),
            mock(de.mhus.vance.brain.thinkengine.ProcessEventEmitter.class),
            mock(de.mhus.vance.brain.scheduling.LaneScheduler.class),
            mock(DocumentExpander.class),
            mock(de.mhus.vance.shared.workspace.WorkspaceService.class),
            mock(de.mhus.vance.shared.document.DocumentService.class),
            mock(org.springframework.beans.factory.ObjectProvider.class),
            mock(de.mhus.vance.brain.inherit.ParentContextSpawnHelper.class),
            mock(de.mhus.vance.brain.inherit.ParentContextRenderer.class));

    private static final MarvinNodeStateMachine.Counters COUNTERS = new MarvinNodeStateMachine.Counters(1, 0, 0, 0);

    private static MarvinNodeDocument nodeWithReplies(String... replies) {
        MarvinNodeDocument node = new MarvinNodeDocument();
        node.setGoal("Analyse the name");
        Map<String, Object> art = new LinkedHashMap<>();
        art.put("recipeReplies", List.of(replies));
        node.setArtifacts(art);
        return node;
    }

    private String phaseMessage(MarvinNodeDocument node, WorkerPhase phase) {
        ThinkProcessDocument process = new ThinkProcessDocument();
        return engine.buildPhaseUserMessage(
                process,
                node,
                phase,
                COUNTERS,
                MarvinNodeStateMachine.Caps.defaults(),
                "PLAN",
                /*nodeDepth*/ 0,
                /*hint*/ null);
    }

    @Test
    void phaseMessage_rendersEveryCallReplyAboveThePhaseInstruction() {
        MarvinNodeDocument node = nodeWithReplies(
                "<<< Result of CALL_RECIPE('web-research'):\nANTWORT-A\n>>>",
                "<<< Result of CALL_RECIPE('web-research'):\nANTWORT-B\n>>>");

        String msg = phaseMessage(node, WorkerPhase.REFLECT);

        // Both answers are the worker's research material — in call order,
        // and all of it above the phase instruction that references it.
        assertThat(msg).contains("ANTWORT-A", "ANTWORT-B");
        assertThat(msg.indexOf("ANTWORT-A")).isLessThan(msg.indexOf("ANTWORT-B"));
        assertThat(msg.indexOf("ANTWORT-B")).isLessThan(msg.indexOf("Phase: REFLECT"));
    }

    @Test
    void phaseMessage_concludeSeesTheMaterialToo() {
        // CONCLUDE composes the final result from "gathered material" —
        // with the replies missing it hallucinated one instead.
        MarvinNodeDocument node = nodeWithReplies("<<< Result of CALL_RECIPE('web-research'):\nBELEG\n>>>");

        assertThat(phaseMessage(node, WorkerPhase.CONCLUDE)).contains("BELEG");
    }

    @Test
    void phaseMessage_withoutReplies_hasNoResultBlock() {
        String msg = phaseMessage(new MarvinNodeDocument(), WorkerPhase.REFLECT);

        assertThat(msg).doesNotContain("Results of previous CALL_RECIPE");
        assertThat(msg).contains("Phase: REFLECT");
    }

    @Test
    void callReplies_toleratesAMissingOrMalformedArtifact() {
        MarvinNodeDocument plain = new MarvinNodeDocument();
        assertThat(MarvinEngine.callReplies(plain)).isEmpty();

        MarvinNodeDocument malformed = new MarvinNodeDocument();
        Map<String, Object> art = new LinkedHashMap<>();
        art.put("recipeReplies", "not-a-list");
        malformed.setArtifacts(art);
        assertThat(MarvinEngine.callReplies(malformed)).isEmpty();
    }
}
