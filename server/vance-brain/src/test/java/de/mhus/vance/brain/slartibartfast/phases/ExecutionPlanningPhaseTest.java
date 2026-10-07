package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ExecutionDecision;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.hactar.HactarService;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.slartibartfast.architect.JsScriptArchitect;
import de.mhus.vance.brain.slartibartfast.architect.VogonArchitect;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The author-only skip at the head of
 * {@link ExecutionPlanningPhase#execute}: schemas whose architect cannot be
 * started by EXECUTING (no recipe output for the resolver, no direct engine
 * spawn — VOGON_PLAN, MAGRATHEA_WORKFLOW) must never reach the decision LLM.
 * Pre-fix, rule 3 of the decision prompt routed a concrete mission into an
 * EXECUTING that failed with "persistedRecipePath has unexpected shape"
 * after a successful authoring run — the plan was written, the run FAILED.
 */
class ExecutionPlanningPhaseTest {

    private final EngineChatFactory chatFactory = mock(EngineChatFactory.class);
    private final VogonArchitect vogonArchitect =
            new VogonArchitect(mock(MagratheaWorkflowLoader.class), mock(RecipeLoader.class));

    private ExecutionPlanningPhase phaseFor(VogonArchitect architect) {
        return new ExecutionPlanningPhase(
                chatFactory,
                mock(LlmCallTracker.class),
                new ObjectMapper(),
                mock(de.mhus.vance.brain.context.LanguageContextResolver.class),
                List.of(architect));
    }

    @Test
    void vogonPlansAreAuthorOnly() {
        // The wiring the skip builds on: a plan is a document somebody else
        // starts, not a child Slart could spawn.
        assertThat(vogonArchitect.supportsChildExecution()).isFalse();
    }

    @Test
    void scriptJsSupportsExecution() {
        JsScriptArchitect scripts = new JsScriptArchitect(mock(HactarService.class));

        assertThat(scripts.supportsChildExecution()).isTrue();
    }

    @Test
    void authorOnlySchemaSkipsWithoutAnLlmCall() {
        ExecutionPlanningPhase phase = phaseFor(vogonArchitect);
        ArchitectState state = ArchitectState.builder()
                .runId("run-1")
                .outputSchemaType(OutputSchemaType.VOGON_PLAN)
                .build();

        phase.execute(state, process(), null);

        assertThat(state.getExecutionDecision()).isEqualTo(ExecutionDecision.SKIP);
        assertThat(state.getExecutionPrompt()).isNull();
        assertThat(state.getExecutionDecisionReason()).contains("author-only").contains("VOGON_PLAN");
        assertThat(state.getIterations()).hasSize(1);
        // The whole point: zero decision-LLM cost, no prompt that could
        // route a concrete mission into an EXECUTING that cannot work.
        verify(chatFactory, never()).forProcess(any(), any(), any());
    }

    @Test
    void anExecutableSchemaStillReachesTheDecisionLlm() {
        // Sentinel: the factory throwing proves the phase walked past the
        // author-only gate and started the decision machinery.
        when(chatFactory.forProcess(any(), any(), any()))
                .thenThrow(new IllegalStateException("reached the decision LLM"));
        ExecutionPlanningPhase phase = new ExecutionPlanningPhase(
                chatFactory,
                mock(LlmCallTracker.class),
                new ObjectMapper(),
                mock(de.mhus.vance.brain.context.LanguageContextResolver.class),
                List.of(new JsScriptArchitect(mock(HactarService.class))));
        ArchitectState state = ArchitectState.builder()
                .runId("run-1")
                .outputSchemaType(OutputSchemaType.SCRIPT_JS)
                .build();

        assertThatThrownBy(() -> phase.execute(state, process(), null))
                .hasMessageContaining("reached the decision LLM");
    }

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("slart-1");
        p.setTenantId("t");
        p.setProjectId("p");
        p.setSessionId("s");
        return p;
    }
}
