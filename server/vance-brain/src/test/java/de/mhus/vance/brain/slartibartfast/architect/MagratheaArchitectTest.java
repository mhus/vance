package de.mhus.vance.brain.slartibartfast.architect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MagratheaArchitect}. Verifies the schema
 * metadata (non-recipe, flat {@code _vance/workflows/} path,
 * author-only) and that {@link MagratheaArchitect#validateDraftShape}
 * delegates to the real {@link MagratheaWorkflowLoader} parser and
 * checks {@code agent_task.recipe} references against the project's
 * recipe inventory.
 */
class MagratheaArchitectTest {

    private RecipeLoader recipeLoader;
    private MagratheaArchitect architect;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        recipeLoader = mock(RecipeLoader.class);
        // The real loader — validateYaml never touches the document
        // layer (it parses a synthetic in-memory hit), so a mock
        // DocumentService is enough.
        MagratheaWorkflowLoader loader =
                new MagratheaWorkflowLoader(mock(DocumentService.class));
        architect = new MagratheaArchitect(loader, recipeLoader);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
    }

    // ──────────────────── schema metadata ────────────────────

    @Test
    void declaresMagratheaWorkflowAsSchemaType() {
        assertThat(architect.type())
                .isEqualTo(OutputSchemaType.MAGRATHEA_WORKFLOW);
    }

    @Test
    void declaresNonRecipeFlatWorkflowPath() {
        assertThat(architect.isRecipeOutput()).isFalse();
        assertThat(architect.persistsAtFlatPath()).isTrue();
        assertThat(architect.outputPathSegment()).isEqualTo("workflows");
        assertThat(architect.outputExtension()).isEqualTo(".yaml");
        assertThat(architect.artefactNoun()).isEqualTo("workflow");
    }

    @Test
    void disablesPathAndExecutionValidationButWantsRecipeListing() {
        assertThat(architect.wantsPathPersistenceCheck()).isFalse();
        assertThat(architect.wantsExecutionValidation()).isFalse();
        assertThat(architect.wantsSubRecipeListing()).isTrue();
    }

    @Test
    void expectedEngineNameIsEmptyForNonRecipeOutput() {
        assertThat(architect.expectedEngineName()).isEmpty();
    }

    @Test
    void systemPromptAndHintTailAreNonEmpty() {
        assertThat(architect.proposingSystemPrompt())
                .isNotBlank()
                .contains("WORKFLOW")
                .contains("start:")
                .contains("states:");
        assertThat(architect.recoveryHintTail(process)).isNotBlank();
    }

    // ──────────────────── validateDraftShape — parse ────────────────────

    @Test
    void validate_passesOnWellFormedWorkflowWithoutAgentTasks() {
        RecipeDraft draft = workflowDraft("greeter", """
                start: done
                states:
                  done:
                    type: terminal
                    outcome: success
                """);
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(
                draft, /*recipeMap*/ null, process, report);

        assertThat(firstFail).isNull();
        assertThat(report).hasSize(2);
        assertThat(report.get(0).getRule())
                .isEqualTo(MagratheaArchitect.RULE_WORKFLOW_PARSES);
        assertThat(report.get(0).isPassed()).isTrue();
        assertThat(report.get(1).getRule())
                .isEqualTo(MagratheaArchitect.RULE_AGENT_TASK_RECIPES_EXIST);
        assertThat(report.get(1).isPassed()).isTrue();
    }

    @Test
    void validate_failsOnUnparseableWorkflow() {
        // Missing the mandatory 'start:' field.
        RecipeDraft draft = workflowDraft("broken", """
                states:
                  done:
                    type: terminal
                """);
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(
                draft, null, process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.isPassed()).isFalse();
        assertThat(firstFail.getRule())
                .isEqualTo(MagratheaArchitect.RULE_WORKFLOW_PARSES);
        assertThat(firstFail.getMessage())
                .contains("rejected the workflow")
                .contains("start");
        assertThat(report).containsExactly(firstFail);
    }

    @Test
    void validate_failsOnDanglingTransitionTarget() {
        RecipeDraft draft = workflowDraft("dangling", """
                start: work
                states:
                  work:
                    type: tool_task
                    tool: doc_read
                    on:
                      success: nowhere
                """);
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(
                draft, null, process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.isPassed()).isFalse();
        assertThat(firstFail.getRule())
                .isEqualTo(MagratheaArchitect.RULE_WORKFLOW_PARSES);
    }

    // ──────────────── validateDraftShape — agent_task recipes ────────────────

    @Test
    void validate_failsWhenAgentTaskRecipeUnknown() {
        when(recipeLoader.listAll("acme", "test-project"))
                .thenReturn(List.of());
        RecipeDraft draft = workflowDraft("bad-agent", """
                start: work
                states:
                  work:
                    type: agent_task
                    recipe: doc_edit
                    on:
                      success: done
                  done:
                    type: terminal
                """);
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(
                draft, null, process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.isPassed()).isFalse();
        assertThat(firstFail.getRule())
                .isEqualTo(MagratheaArchitect.RULE_AGENT_TASK_RECIPES_EXIST);
        assertThat(firstFail.getMessage())
                .contains("doc_edit")
                .contains("ford");
        // The parse check still passed and precedes the recipe check.
        assertThat(report).hasSize(2);
        assertThat(report.get(0).isPassed()).isTrue();
    }

    @Test
    void validate_passesWhenAgentTaskRecipeKnown() {
        ResolvedRecipe ford = mock(ResolvedRecipe.class);
        when(ford.name()).thenReturn("ford");
        when(recipeLoader.listAll("acme", "test-project"))
                .thenReturn(List.of(ford));
        RecipeDraft draft = workflowDraft("good-agent", """
                start: work
                states:
                  work:
                    type: agent_task
                    recipe: ford
                    on:
                      success: done
                  done:
                    type: terminal
                """);
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(
                draft, null, process, report);

        assertThat(firstFail).isNull();
        assertThat(report.get(1).getRule())
                .isEqualTo(MagratheaArchitect.RULE_AGENT_TASK_RECIPES_EXIST);
        assertThat(report.get(1).isPassed()).isTrue();
    }

    // ──────────────────── appendProposingContext ────────────────────

    @Test
    void appendProposingContext_listsRecipesWhenAvailable() {
        ResolvedRecipe ford = mock(ResolvedRecipe.class);
        when(ford.name()).thenReturn("ford");
        when(ford.engine()).thenReturn("frankie");
        when(ford.description()).thenReturn("generalist worker");
        StringBuilder sb = new StringBuilder();

        architect.appendProposingContext(sb, /*state*/ null, List.of(ford));

        assertThat(sb.toString())
                .contains("Available recipes")
                .contains("ford")
                .contains("frankie")
                .contains("generalist worker");
    }

    @Test
    void appendProposingContext_fallsBackToFordWhenNoRecipes() {
        StringBuilder sb = new StringBuilder();

        architect.appendProposingContext(sb, null, List.of());

        assertThat(sb.toString())
                .contains("(none)")
                .contains("ford");
    }

    // ──────────────────── extractRecipeName (inherited default) ────────────────────

    @Test
    void extractRecipeName_usesDefaultImplementation() {
        assertThat(architect.extractRecipeName(
                java.util.Map.of("name", "my-flow", "yaml", "start: x")))
                .isEqualTo("my-flow");
    }

    // ──────────────────── helpers ────────────────────

    private static RecipeDraft workflowDraft(String name, String yaml) {
        return RecipeDraft.builder()
                .name(name)
                .yaml(yaml)
                .outputSchemaType(OutputSchemaType.MAGRATHEA_WORKFLOW)
                .confidence(0.8)
                .build();
    }
}
