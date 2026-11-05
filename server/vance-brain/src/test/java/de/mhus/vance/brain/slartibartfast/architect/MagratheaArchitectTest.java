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
    private de.mhus.vance.brain.tools.ToolDispatcher toolDispatcher;
    private MagratheaArchitect architect;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        recipeLoader = mock(RecipeLoader.class);
        // The real loader — validateYaml never touches the document
        // layer (it parses a synthetic in-memory hit), so a mock
        // DocumentService is enough.
        MagratheaWorkflowLoader loader = new MagratheaWorkflowLoader(mock(DocumentService.class));
        toolDispatcher = mock(de.mhus.vance.brain.tools.ToolDispatcher.class);
        architect = new MagratheaArchitect(loader, recipeLoader, toolDispatcher);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
    }

    // ──────────────────── schema metadata ────────────────────

    @Test
    void declaresMagratheaWorkflowAsSchemaType() {
        assertThat(architect.type()).isEqualTo(OutputSchemaType.MAGRATHEA_WORKFLOW);
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

        ValidationCheck firstFail = architect.validateDraftShape(draft, /*recipeMap*/ null, process, report);

        assertThat(firstFail).isNull();
        assertThat(report).hasSize(3);
        assertThat(report.get(0).getRule()).isEqualTo(MagratheaArchitect.RULE_WORKFLOW_PARSES);
        assertThat(report.get(0).isPassed()).isTrue();
        assertThat(report.get(1).getRule()).isEqualTo(MagratheaArchitect.RULE_AGENT_TASK_RECIPES_EXIST);
        assertThat(report.get(1).isPassed()).isTrue();
        assertThat(report.get(2).getRule()).isEqualTo(MagratheaArchitect.RULE_TOOL_TASK_TOOLS_EXIST);
        assertThat(report.get(2).isPassed()).isTrue();
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

        ValidationCheck firstFail = architect.validateDraftShape(draft, null, process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.isPassed()).isFalse();
        assertThat(firstFail.getRule()).isEqualTo(MagratheaArchitect.RULE_WORKFLOW_PARSES);
        assertThat(firstFail.getMessage()).contains("rejected the workflow").contains("start");
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

        ValidationCheck firstFail = architect.validateDraftShape(draft, null, process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.isPassed()).isFalse();
        assertThat(firstFail.getRule()).isEqualTo(MagratheaArchitect.RULE_WORKFLOW_PARSES);
    }

    // ──────────────── validateDraftShape — agent_task recipes ────────────────

    @Test
    void validate_failsWhenAgentTaskRecipeUnknown() {
        when(recipeLoader.listAll("acme", "test-project")).thenReturn(List.of());
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

        ValidationCheck firstFail = architect.validateDraftShape(draft, null, process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.isPassed()).isFalse();
        assertThat(firstFail.getRule()).isEqualTo(MagratheaArchitect.RULE_AGENT_TASK_RECIPES_EXIST);
        assertThat(firstFail.getMessage()).contains("doc_edit").contains("ford");
        // The parse check still passed and precedes the recipe check.
        assertThat(report).hasSize(2);
        assertThat(report.get(0).isPassed()).isTrue();
    }

    @Test
    void validate_passesWhenAgentTaskRecipeKnown() {
        ResolvedRecipe ford = mock(ResolvedRecipe.class);
        when(ford.name()).thenReturn("ford");
        when(recipeLoader.listAll("acme", "test-project")).thenReturn(List.of(ford));
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

        ValidationCheck firstFail = architect.validateDraftShape(draft, null, process, report);

        assertThat(firstFail).isNull();
        assertThat(report.get(1).getRule()).isEqualTo(MagratheaArchitect.RULE_AGENT_TASK_RECIPES_EXIST);
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

        assertThat(sb.toString()).contains("(none)").contains("ford");
    }

    // ──────────────────── extractRecipeName (inherited default) ────────────────────

    // ──────────────────── tool catalogue and tool_task check ────────────────────

    private static final String TOOL_YAML = """
            description: calls a tool
            start: call
            states:
              call:
                type: tool_task
                tool: %s
                params: {}
                on:
                  success: done
              done:
                type: terminal
                outcome: success
            """;

    private void tools(de.mhus.vance.toolpack.Tool... tools) {
        de.mhus.vance.brain.tools.ToolSource source = mock(de.mhus.vance.brain.tools.ToolSource.class);
        when(source.sourceId()).thenReturn("SERVER");
        List<de.mhus.vance.brain.tools.ToolDispatcher.Resolved> resolved = new ArrayList<>();
        for (de.mhus.vance.toolpack.Tool t : tools) {
            resolved.add(new de.mhus.vance.brain.tools.ToolDispatcher.Resolved(t, source));
        }
        when(toolDispatcher.resolveAll(org.mockito.ArgumentMatchers.any())).thenReturn(resolved);
    }

    private static de.mhus.vance.toolpack.Tool tool(
            String name, String service, java.util.Map<String, Object> params, java.util.Map<String, Object> output) {
        return new de.mhus.vance.toolpack.Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public boolean primary() {
                return false;
            }

            @Override
            public java.util.Map<String, Object> paramsSchema() {
                return params;
            }

            @Override
            public java.util.Map<String, Object> outputSchema() {
                return output;
            }

            @Override
            public String service() {
                return service;
            }

            @Override
            public java.util.Map<String, Object> invoke(
                    java.util.Map<String, Object> p, de.mhus.vance.toolpack.ToolInvocationContext c) {
                return java.util.Map.of();
            }
        };
    }

    private static final java.util.Map<String, Object> ISSUE_PARAMS = java.util.Map.of(
            "type", "object",
            "properties", orderedProps(),
            "required", List.of("project"));

    private static java.util.Map<String, Object> orderedProps() {
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("project", java.util.Map.of());
        props.put("summary", java.util.Map.of());
        return props;
    }

    private static final java.util.Map<String, Object> ISSUE_OUTPUT = java.util.Map.of(
            "type",
            "object",
            "properties",
            java.util.Map.of("json", java.util.Map.of("properties", java.util.Map.of("key", java.util.Map.of()))));

    @Test
    void appendProposingContext_listsIntegrationToolsWithParametersAndResultKeys() {
        tools(
                tool("jira_rest__createIssue", "jira_rest", ISSUE_PARAMS, ISSUE_OUTPUT),
                tool("doc_read", null, java.util.Map.of("type", "object"), null));
        StringBuilder sb = new StringBuilder();

        architect.appendProposingContext(sb, null, List.of(), process);

        String text = sb.toString();
        assertThat(text).contains("jira_rest:").contains("jira_rest__createIssue(project*, summary)");
        assertThat(text).contains("-> json{key}");
        // Built-ins are not listed — there are hundreds and most are no workflow step.
        assertThat(text).doesNotContain("doc_read(");
    }

    @Test
    void appendProposingContext_addsNoToolBlockWithoutIntegrations() {
        tools(tool("doc_read", null, java.util.Map.of("type", "object"), null));
        StringBuilder sb = new StringBuilder();

        architect.appendProposingContext(sb, null, List.of(), process);

        assertThat(sb.toString()).doesNotContain("integration tools");
    }

    @Test
    void validate_failsWhenToolTaskNamesAToolThatDoesNotExist() {
        tools(tool("jira_rest__createIssue", "jira_rest", ISSUE_PARAMS, ISSUE_OUTPUT));
        RecipeDraft draft = RecipeDraft.builder()
                .name("wf")
                .yaml(TOOL_YAML.formatted("jira.create_issue"))
                .build();
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck failed = architect.validateDraftShape(draft, null, process, report);

        assertThat(failed).isNotNull();
        assertThat(failed.getRule()).isEqualTo(MagratheaArchitect.RULE_TOOL_TASK_TOOLS_EXIST);
        // The correction names what exists, and the nearest name shares a word with the wrong one.
        assertThat(failed.getMessage()).contains("jira.create_issue").contains("jira_rest__createIssue");
    }

    @Test
    void validate_passesWhenToolTaskNamesAKnownTool() {
        tools(tool("jira_rest__createIssue", "jira_rest", ISSUE_PARAMS, ISSUE_OUTPUT));
        RecipeDraft draft = RecipeDraft.builder()
                .name("wf")
                .yaml(TOOL_YAML.formatted("jira_rest__createIssue"))
                .build();

        assertThat(architect.validateDraftShape(draft, null, process, new ArrayList<>()))
                .isNull();
    }

    @Test
    void validate_doesNotFailAWorkflowWhenTheToolInventoryCannotBeRead() {
        when(toolDispatcher.resolveAll(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("down"));
        RecipeDraft draft = RecipeDraft.builder()
                .name("wf")
                .yaml(TOOL_YAML.formatted("whatever"))
                .build();

        assertThat(architect.validateDraftShape(draft, null, process, new ArrayList<>()))
                .isNull();
    }

    @Test
    void validate_skipsTheToolCheckForAToolNameBuiltFromAPlaceholder() {
        tools();
        RecipeDraft draft = RecipeDraft.builder()
                .name("wf")
                .yaml(TOOL_YAML.formatted("\"${params.tool}\""))
                .build();

        assertThat(architect.validateDraftShape(draft, null, process, new ArrayList<>()))
                .isNull();
    }

    @Test
    void extractRecipeName_usesDefaultImplementation() {
        assertThat(architect.extractRecipeName(java.util.Map.of("name", "my-flow", "yaml", "start: x")))
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
