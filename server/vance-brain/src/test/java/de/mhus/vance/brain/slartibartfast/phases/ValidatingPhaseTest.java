package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ValidatingPhase}. Pure-logic gate —
 * verifies YAML parse, recipe shape, embedded
 * {@code strategyPlanYaml} parse via Vogon's resolver, and the
 * justification-resolves rule.
 */
class ValidatingPhaseTest {

    private ValidatingPhase phase;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;
    private RecipeLoader recipeLoader;

    @BeforeEach
    void setUp() {
        recipeLoader = mock(RecipeLoader.class);
        when(recipeLoader.listAll(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of());
        phase = new ValidatingPhase(recipeLoader);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        ctx = mock(ThinkEngineContext.class);
    }

    @Test
    void wellFormedRecipe_passes() {
        ArchitectState state = stateWith(
                draft(VALID_RECIPE_YAML, Map.of(
                        "name", "sg1",
                        "phases.0.worker", "sg1")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
        assertThat(state.getValidationReport())
                .extracting(ValidationCheck::isPassed)
                .containsOnly(true);
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.VALIDATING)
                .extracting(PhaseIteration::getOutcome)
                .containsExactly(PhaseIteration.IterationOutcome.PASSED);
    }

    @Test
    void missingRecipeDraft_setsFailureReason() {
        ArchitectState state = ArchitectState.builder().runId("run1").build();

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .contains("VALIDATING entered without a proposedRecipe");
    }

    @Test
    void marvinRecipeWellFormed_passes() {
        String yaml = """
                name: x
                description: x
                engine: marvin
                params:
                  rootTaskKind: PLAN
                  maxPlanCorrections: 2
                promptPrefix: |
                  Du bist der x-PLAN-Knoten.
                  Erzeuge GENAU 1 Child:
                    {"taskKind":"WORKER","goal":"...","taskSpec":{}}
                """;
        ArchitectState state = stateWith(
                RecipeDraft.builder()
                        .name("x")
                        .outputSchemaType(OutputSchemaType.MARVIN_RECIPE)
                        .yaml(yaml)
                        .justifications(Map.of("promptPrefix", "sg1"))
                        .build(),
                List.of(subgoal("sg1")));
        state.setOutputSchemaType(OutputSchemaType.MARVIN_RECIPE);

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
        assertThat(state.getValidationReport())
                .filteredOn(v -> ValidatingPhase.RULE_MARVIN_PROMPT_PREFIX.equals(v.getRule()))
                .extracting(ValidationCheck::isPassed)
                .containsExactly(true);
    }

    @Test
    void marvinRecipeMissingPromptPrefix_triggersRecovery() {
        String yaml = """
                name: x
                description: x
                engine: marvin
                params:
                  rootTaskKind: PLAN
                """;
        ArchitectState state = stateWith(
                RecipeDraft.builder()
                        .name("x")
                        .outputSchemaType(OutputSchemaType.MARVIN_RECIPE)
                        .yaml(yaml)
                        .justifications(Map.of("name", "sg1"))
                        .build(),
                List.of(subgoal("sg1")));
        state.setOutputSchemaType(OutputSchemaType.MARVIN_RECIPE);

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_MARVIN_PROMPT_PREFIX);
    }

    @Test
    void marvinRecipeWithVogonEngine_triggersRecovery() {
        String yaml = """
                name: x
                description: x
                engine: vogon
                params:
                  rootTaskKind: PLAN
                promptPrefix: |
                  ...
                """;
        ArchitectState state = stateWith(
                RecipeDraft.builder()
                        .name("x")
                        .outputSchemaType(OutputSchemaType.MARVIN_RECIPE)
                        .yaml(yaml)
                        .justifications(Map.of("name", "sg1"))
                        .build(),
                List.of(subgoal("sg1")));
        state.setOutputSchemaType(OutputSchemaType.MARVIN_RECIPE);

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_RECIPE_SHAPE);
        assertThat(state.getValidationReport())
                .filteredOn(v -> !v.isPassed())
                .extracting(ValidationCheck::getMessage)
                .first().asString()
                .contains("requires engine='marvin'");
    }

    @Test
    void malformedYaml_triggersRecovery() {
        ArchitectState state = stateWith(
                draft("not: valid:\n  yaml: : :", Map.of("name", "sg1")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_YAML_PARSES);
    }

    @Test
    void yamlNotMapping_triggersRecovery() {
        ArchitectState state = stateWith(
                draft("- just\n- a list\n", Map.of("name", "sg1")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_YAML_PARSES);
        assertThat(state.getValidationReport())
                .filteredOn(v -> ValidatingPhase.RULE_YAML_PARSES.equals(v.getRule())
                        && !v.isPassed())
                .extracting(ValidationCheck::getMessage)
                .first().asString().contains("not a mapping");
    }

    @Test
    void missingTopLevelEngine_triggersRecovery() {
        String yaml = """
                name: x
                description: x
                params:
                  strategyPlanYaml: |
                    name: x-strategy
                    version: "1"
                    phases:
                      - name: only
                        worker: ford
                        gate: { requires: [only_completed] }
                """;
        ArchitectState state = stateWith(
                draft(yaml, Map.of("name", "sg1")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_RECIPE_SHAPE);
    }

    @Test
    void wrongEngine_triggersRecovery() {
        String yaml = """
                name: x
                description: x
                engine: ford
                params:
                  strategyPlanYaml: |
                    name: x-strategy
                    version: "1"
                    phases:
                      - name: only
                        worker: ford
                        gate: { requires: [only_completed] }
                """;
        ArchitectState state = stateWith(
                draft(yaml, Map.of("name", "sg1")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_RECIPE_SHAPE);
        assertThat(state.getValidationReport())
                .filteredOn(v -> !v.isPassed())
                .extracting(ValidationCheck::getMessage)
                .first().asString().contains("ford");
    }

    @Test
    void missingStrategyPlanYaml_triggersRecovery() {
        String yaml = """
                name: x
                description: x
                engine: vogon
                params:
                  someOther: thing
                """;
        ArchitectState state = stateWith(
                draft(yaml, Map.of("name", "sg1")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_VOGON_STRATEGY_PARSES);
    }

    @Test
    void invalidEmbeddedStrategyYaml_triggersRecovery() {
        // Strategy yaml exists but is malformed for the resolver
        // (no phases array).
        String yaml = """
                name: x
                description: x
                engine: vogon
                params:
                  strategyPlanYaml: |
                    name: malformed
                    version: "1"
                    not_phases: nope
                """;
        ArchitectState state = stateWith(
                draft(yaml, Map.of("name", "sg1")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_VOGON_STRATEGY_PARSES);
    }

    @Test
    void danglingJustificationRef_triggersRecovery() {
        ArchitectState state = stateWith(
                draft(VALID_RECIPE_YAML, Map.of(
                        "name", "sg-ghost",            // not in subgoals
                        "phases.0.worker", "sg1")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(ValidatingPhase.RULE_JUSTIFICATION_RESOLVES);
        assertThat(state.getPendingRecovery().getOffendingId())
                .isEqualTo("name");
    }

    @Test
    void commaSeparatedJustificationValue_allResolve_passes() {
        // The LLM occasionally emits "sg1, sg2, sg3" as a single
        // value when one constraint covers several subgoals. We
        // accept that shape as long as every part is a real sg-id.
        ArchitectState state = stateWith(
                draft(VALID_RECIPE_YAML, Map.of(
                        "name", "sg1",
                        "phases.0.worker", "sg1, sg2 ,sg3")),
                List.of(subgoal("sg1"), subgoal("sg2"), subgoal("sg3")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
        assertThat(state.getValidationReport())
                .filteredOn(c -> ValidatingPhase.RULE_JUSTIFICATION_RESOLVES.equals(c.getRule()))
                .extracting(ValidationCheck::isPassed)
                .containsExactly(true);
    }

    @Test
    void commaSeparatedJustificationValue_oneInvalid_rejected() {
        ArchitectState state = stateWith(
                draft(VALID_RECIPE_YAML, Map.of(
                        "name", "sg1",
                        "phases.0.worker", "sg1, sg-ghost, sg3")),
                List.of(subgoal("sg1"), subgoal("sg3")));

        phase.execute(state, process, ctx);

        assertThat(state.getValidationReport())
                .filteredOn(c -> ValidatingPhase.RULE_JUSTIFICATION_RESOLVES.equals(c.getRule())
                        && !c.isPassed())
                .extracting(ValidationCheck::getMessage)
                .singleElement().asString()
                .contains("sg-ghost");
    }

    @Test
    void hintMentionsAllFailingRules() {
        ArchitectState state = stateWith(
                draft("not a mapping\n", Map.of("name", "sg-ghost")),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        String hint = state.getPendingRecovery().getHint();
        assertThat(hint).contains(ValidatingPhase.RULE_YAML_PARSES);
    }

    // ──────────────────── MARVIN_RECIPE recipe-existence rule ────────────────────

    @Test
    void marvinRecipe_unknownRecipeName_rejected() {
        ArchitectState state = marvinState(MARVIN_YAML_WITH_RECIPES_TEMPLATE.formatted(
                        "    - web-research\n    - real-recipe", ""),
                Map.of("name", "sg1", "promptPrefix", "sg1"),
                List.of(subgoal("sg1")));
        when(recipeLoader.listAll("acme", "test-project"))
                .thenReturn(java.util.List.of(stubRecipe("real-recipe")));

        phase.execute(state, process, ctx);

        assertThat(state.getValidationReport())
                .filteredOn(c -> ValidatingPhase.RULE_MARVIN_RECIPES_EXIST.equals(c.getRule())
                        && !c.isPassed())
                .extracting(ValidationCheck::getMessage)
                .singleElement().asString()
                .contains("web-research");
    }

    @Test
    void marvinRecipe_engineNameAsRecipe_rejectedAsUnknown() {
        // 'marvin-worker' is Marvin's engine label, not a recipe.
        // The existence check must reject it just like any other
        // hallucinated name — no special-case engine list.
        ArchitectState state = marvinState(MARVIN_YAML_WITH_RECIPES_TEMPLATE.formatted(
                        "    - marvin-worker\n    - real-recipe", ""),
                Map.of("name", "sg1", "promptPrefix", "sg1"),
                List.of(subgoal("sg1")));
        when(recipeLoader.listAll("acme", "test-project"))
                .thenReturn(java.util.List.of(stubRecipe("real-recipe")));

        phase.execute(state, process, ctx);

        assertThat(state.getValidationReport())
                .filteredOn(c -> ValidatingPhase.RULE_MARVIN_RECIPES_EXIST.equals(c.getRule())
                        && !c.isPassed())
                .extracting(ValidationCheck::getMessage)
                .singleElement().asString()
                .contains("not found in project")
                .contains("marvin-worker");
    }

    @Test
    void marvinRecipe_duplicateRecipeName_rejected() {
        ArchitectState state = marvinState(MARVIN_YAML_WITH_RECIPES_TEMPLATE.formatted(
                        "    - real-recipe\n    - real-recipe", ""),
                Map.of("name", "sg1", "promptPrefix", "sg1"),
                List.of(subgoal("sg1")));
        when(recipeLoader.listAll("acme", "test-project"))
                .thenReturn(java.util.List.of(stubRecipe("real-recipe")));

        phase.execute(state, process, ctx);

        assertThat(state.getValidationReport())
                .filteredOn(c -> ValidatingPhase.RULE_MARVIN_RECIPES_EXIST.equals(c.getRule())
                        && !c.isPassed())
                .extracting(ValidationCheck::getMessage)
                .singleElement().asString()
                .contains("duplicate")
                .contains("real-recipe");
    }

    @Test
    void marvinRecipe_emptyAllowedList_passes() {
        // No allowedSubTaskRecipes — fully open-ended Marvin run.
        ArchitectState state = marvinState(MARVIN_YAML_NO_RECIPE_LIST,
                Map.of("name", "sg1", "promptPrefix", "sg1"),
                List.of(subgoal("sg1")));

        phase.execute(state, process, ctx);

        assertThat(state.getValidationReport())
                .filteredOn(c -> ValidatingPhase.RULE_MARVIN_RECIPES_EXIST.equals(c.getRule()))
                .extracting(ValidationCheck::isPassed)
                .containsExactly(true);
    }

    @Test
    void marvinRecipe_allRecipesPresent_passes() {
        ArchitectState state = marvinState(MARVIN_YAML_WITH_RECIPES_TEMPLATE.formatted(
                        "    - real-recipe\n    - other-recipe",
                        "    - other-recipe"),
                Map.of("name", "sg1", "promptPrefix", "sg1"),
                List.of(subgoal("sg1")));
        when(recipeLoader.listAll("acme", "test-project"))
                .thenReturn(java.util.List.of(
                        stubRecipe("real-recipe"),
                        stubRecipe("other-recipe")));

        phase.execute(state, process, ctx);

        assertThat(state.getValidationReport())
                .filteredOn(c -> ValidatingPhase.RULE_MARVIN_RECIPES_EXIST.equals(c.getRule()))
                .extracting(ValidationCheck::isPassed)
                .containsExactly(true);
    }

    @Test
    void marvinRecipe_recoveryHintListsAvailableRecipes() {
        ArchitectState state = marvinState(MARVIN_YAML_WITH_RECIPES_TEMPLATE.formatted(
                        "    - bogus-recipe", ""),
                Map.of("name", "sg1", "promptPrefix", "sg1"),
                List.of(subgoal("sg1")));
        when(recipeLoader.listAll("acme", "test-project"))
                .thenReturn(java.util.List.of(stubRecipe("real-recipe")));

        phase.execute(state, process, ctx);

        String hint = state.getPendingRecovery().getHint();
        assertThat(hint)
                .contains("real-recipe")
                .contains("Valid recipe names");
    }

    // ──────────────────── helpers ────────────────────

    private static final String VALID_RECIPE_YAML = """
            name: x
            description: x
            engine: vogon
            params:
              strategyPlanYaml: |
                name: x-strategy
                version: "1"
                phases:
                  - name: only
                    worker: ford
                    gate: { requires: [only_completed] }
            """;

    /**
     * Template for a MARVIN_RECIPE yaml with two slots (positional):
     * {@code %s} #1 = body of {@code allowedSubTaskRecipes} list,
     * {@code %s} #2 = body of {@code recipesOnlyViaExpand} list (may
     * be empty string for no entries).
     */
    private static final String MARVIN_YAML_WITH_RECIPES_TEMPLATE = """
            name: m
            description: m
            engine: marvin
            params:
              rootTaskKind: PLAN
              allowedSubTaskRecipes:
            %s
              recipesOnlyViaExpand:
            %s
            promptPrefix: |
              Drive the plan.
            """;

    private static final String MARVIN_YAML_NO_RECIPE_LIST = """
            name: m
            description: m
            engine: marvin
            params:
              rootTaskKind: PLAN
            promptPrefix: |
              Drive the plan.
            """;

    private static ArchitectState marvinState(
            String yaml, Map<String, String> just, List<Subgoal> subgoals) {
        return ArchitectState.builder()
                .runId("run1")
                .outputSchemaType(OutputSchemaType.MARVIN_RECIPE)
                .proposedRecipe(RecipeDraft.builder()
                        .name("m")
                        .outputSchemaType(OutputSchemaType.MARVIN_RECIPE)
                        .yaml(yaml)
                        .justifications(new LinkedHashMap<>(just))
                        .confidence(0.8)
                        .build())
                .subgoals(new java.util.ArrayList<>(subgoals))
                .build();
    }

    private static ArchitectState stateWith(
            RecipeDraft draft, List<Subgoal> subgoals) {
        return ArchitectState.builder()
                .runId("run1")
                .outputSchemaType(OutputSchemaType.VOGON_STRATEGY)
                .proposedRecipe(draft)
                .subgoals(new java.util.ArrayList<>(subgoals))
                .build();
    }

    private static RecipeDraft draft(String yaml, Map<String, String> just) {
        return RecipeDraft.builder()
                .name("test-draft")
                .outputSchemaType(OutputSchemaType.VOGON_STRATEGY)
                .yaml(yaml)
                .justifications(new LinkedHashMap<>(just))
                .confidence(0.8)
                .build();
    }

    private static Subgoal subgoal(String id) {
        return Subgoal.builder()
                .id(id).goal("g " + id)
                .evidenceRefs(List.of("cl1"))
                .criterionRefs(List.of("cr1"))
                .speculative(false).build();
    }

    private static de.mhus.vance.brain.recipe.ResolvedRecipe stubRecipe(String name) {
        return new de.mhus.vance.brain.recipe.ResolvedRecipe(
                name,
                "stub recipe " + name,
                "ford",
                java.util.Map.of(),
                /*promptPrefix*/ null,
                /*promptPrefixSmall*/ null,
                de.mhus.vance.api.thinkprocess.PromptMode.APPEND,
                /*dataRelayCorrection*/ null,
                java.util.List.of(),
                java.util.List.of(),
                java.util.Map.of(),
                java.util.List.of(),
                /*allowedSkills*/ null,
                /*locked*/ false,
                java.util.List.of(),
                de.mhus.vance.brain.recipe.RecipeSource.PROJECT);
    }
}
