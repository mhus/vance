package de.mhus.vance.brain.slartibartfast.architect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Unit tests for {@link BenjyArchitect}. Verifies the schema metadata
 * (recipe output, author-only validation flags, recipe listing) and
 * that {@link BenjyArchitect#validateDraftShape} delegates the shape
 * check to the Benjy engine's own fail-fast contract
 * ({@code BenjyFeatureConfig.fromParams}) and resolves every recipe
 * reference against the project inventory — unknown references and
 * kind mismatches (LightLm profile vs spawnable worker) fail with a
 * hint that lists what is actually available.
 */
class BenjyArchitectTest {

    private RecipeLoader recipeLoader;
    private BenjyArchitect architect;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        recipeLoader = mock(RecipeLoader.class);
        architect = new BenjyArchitect(recipeLoader);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
    }

    // ──────────────────── schema metadata ────────────────────

    @Test
    void declaresBenjyRecipeAsSchemaType() {
        assertThat(architect.type()).isEqualTo(OutputSchemaType.BENJY_RECIPE);
        assertThat(architect.expectedEngineName()).isEqualTo("benjy");
        assertThat(architect.isRecipeOutput()).isTrue();
    }

    @Test
    void disablesPathAndExecutionValidationButWantsRecipeListing() {
        assertThat(architect.wantsPathPersistenceCheck()).isFalse();
        assertThat(architect.wantsExecutionValidation()).isFalse();
        assertThat(architect.wantsSubRecipeListing()).isTrue();
    }

    @Test
    void systemPromptAndHintTailAreNonEmpty() {
        assertThat(architect.proposingSystemPrompt())
                .isNotBlank()
                .contains("engine: benjy")
                .contains("interpret")
                .contains("escalation");
        assertThat(architect.recoveryHintTail(process)).isNotBlank();
    }

    @Test
    void appendProposingContext_marksInternalAndSpawnableRoles() {
        ResolvedRecipe profile = recipe("benjy-interpret", "jeltz", true);
        ResolvedRecipe doer = recipe("benjy-do-coding", "ford", false);
        when(recipeLoader.listAll("acme", "test-project")).thenReturn(List.of(profile, doer));

        StringBuilder sb = new StringBuilder();
        architect.appendProposingContext(sb, new ArchitectState(), List.of(profile, doer));

        assertThat(sb.toString())
                .contains("benjy-interpret")
                .contains("internal LightLm profile")
                .contains("benjy-do-coding")
                .contains("spawnable worker");
    }

    // ──────────────────── validateDraftShape ────────────────────

    @Test
    void validate_passesOnWellFormedFullPipelineDraft() {
        stubAllReferencesResolvable();
        RecipeDraft draft = draft(fullPipelineYaml());
        Map<String, Object> recipeMap = new Yaml().load(fullPipelineYaml());
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(draft, recipeMap, process, report);

        assertThat(firstFail).isNull();
        assertThat(report).hasSize(4).allMatch(ValidationCheck::isPassed);
        assertThat(report.get(0).getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_PARAMS_MAP_PRESENT);
        assertThat(report.get(1).getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_FEATURE_CONFIG);
        assertThat(report.get(2).getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_RECIPE_REFS_EXIST);
        assertThat(report.get(3).getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_RECIPE_KINDS);
    }

    @Test
    void validate_failsOnMissingParamsMap() {
        String yaml = """
                description: |
                  No params block.
                engine: benjy
                """;
        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = architect.validateDraftShape(draft(yaml), new Yaml().load(yaml), process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_PARAMS_MAP_PRESENT);
        assertThat(firstFail.getMessage()).contains("'params' map");
    }

    @Test
    void validate_failsOnUnknownFeatureKeyViaEngineContract() {
        // The engine's fail-fast contract (BenjyFeatureConfig) must be
        // the authority — an unknown feature key fails here with the
        // engine's own message, not a parallel architect wording.
        String yaml = """
                description: |
                  Unknown feature.
                engine: benjy
                params:
                  doRecipe: benjy-do-coding
                  features:
                    interpret: { recipe: benjy-interpret }
                    interpred: { recipe: benjy-interpret }
                """;
        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = architect.validateDraftShape(draft(yaml), new Yaml().load(yaml), process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_FEATURE_CONFIG);
        assertThat(firstFail.getMessage()).contains("interpred").contains("not a known feature");
    }

    @Test
    void validate_failsOnMissingDoRecipeViaEngineContract() {
        String yaml = """
                description: |
                  Missing doRecipe.
                engine: benjy
                params:
                  features:
                    interpret: { recipe: benjy-interpret }
                """;
        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = architect.validateDraftShape(draft(yaml), new Yaml().load(yaml), process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_FEATURE_CONFIG);
        assertThat(firstFail.getMessage()).contains("doRecipe is required");
    }

    @Test
    void validate_failsOnUnresolvableRecipeReferenceWithInventoryHint() {
        when(recipeLoader.load(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        ResolvedRecipe known = recipe("benjy-do-coding", "ford", false);
        when(recipeLoader.listAll("acme", "test-project")).thenReturn(List.of(known));

        String yaml = """
                description: |
                  Broken reference.
                engine: benjy
                params:
                  doRecipe: benjy-do-missing
                  features:
                    interpret: { recipe: benjy-interpret }
                """;
        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = architect.validateDraftShape(draft(yaml), new Yaml().load(yaml), process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_RECIPE_REFS_EXIST);
        assertThat(firstFail.getMessage()).contains("benjy-do-missing").contains("benjy-do-coding");
    }

    @Test
    void validate_failsWhenControllerFeatureReferencesSpawnableWorker() {
        // interpret/route/evaluate/reflect go through LightLlmService,
        // which requires internal: true — a spawnable worker recipe at
        // a controller slot would die mid-run, not at validation time.
        stubAllReferencesSpawnable();
        String yaml = """
                description: |
                  Controller slot holds a spawnable worker.
                engine: benjy
                params:
                  doRecipe: benjy-do-coding
                  features:
                    interpret: { recipe: benjy-do-coding }
                """;
        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = architect.validateDraftShape(draft(yaml), new Yaml().load(yaml), process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_RECIPE_KINDS);
        assertThat(firstFail.getMessage()).contains("not an internal LightLm profile");
    }

    @Test
    void validate_failsWhenDoRecipeReferencesInternalProfile() {
        ResolvedRecipe profile = recipe("benjy-interpret", "jeltz", true);
        when(recipeLoader.load(anyString(), anyString(), anyString())).thenReturn(Optional.of(profile));
        when(recipeLoader.listAll("acme", "test-project")).thenReturn(List.of(profile));

        String yaml = """
                description: |
                  Doer slot holds an internal config profile.
                engine: benjy
                params:
                  doRecipe: benjy-interpret
                  features:
                    interpret: { recipe: benjy-interpret }
                """;
        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = architect.validateDraftShape(draft(yaml), new Yaml().load(yaml), process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.getRule()).isEqualTo(BenjyArchitect.RULE_BENJY_RECIPE_KINDS);
        assertThat(firstFail.getMessage()).contains("not a spawnable worker");
    }

    // ──────────────────── fixtures ────────────────────

    private static String fullPipelineYaml() {
        return """
                description: |
                  Full pipeline test draft.
                engine: benjy
                params:
                  doRecipe: benjy-do-coding
                  taskTypes: [info, coding]
                  features:
                    interpret: { recipe: benjy-interpret }
                    route: { recipe: benjy-route }
                    check: { command: "mvn -q test" }
                    evaluate: { recipe: benjy-evaluate }
                    reflect: { recipe: benjy-reflect }
                    escalation: { recipe: coding }
                  maxInitialItems: 5
                  workTarget:
                    kind: WORK
                """;
    }

    private static RecipeDraft draft(String yaml) {
        return RecipeDraft.builder()
                .name("benjy-test")
                .outputSchemaType(OutputSchemaType.BENJY_RECIPE)
                .yaml(yaml)
                .build();
    }

    private static ResolvedRecipe recipe(String name, String engine, boolean internal) {
        ResolvedRecipe r = mock(ResolvedRecipe.class);
        when(r.name()).thenReturn(name);
        when(r.engine()).thenReturn(engine);
        when(r.internal()).thenReturn(internal);
        when(r.description()).thenReturn("test recipe " + name);
        return r;
    }

    private void stub(String name, boolean internal) {
        // Build the mock BEFORE opening the loader stub — creating it
        // (which stubs its own accessors) inside when(...) is an
        // unfinished-stubbing error at Mockito runtime.
        ResolvedRecipe r = recipe(name, "jeltz", internal);
        when(recipeLoader.load("acme", "test-project", name)).thenReturn(Optional.of(r));
    }

    private void stubAllReferencesResolvable() {
        stub("benjy-do-coding", false);
        stub("coding", false);
        stub("benjy-interpret", true);
        stub("benjy-route", true);
        stub("benjy-evaluate", true);
        stub("benjy-reflect", true);
    }

    private void stubAllReferencesSpawnable() {
        stub("benjy-do-coding", false);
        stub("benjy-interpret", false);
    }
}
