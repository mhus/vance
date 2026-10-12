package de.mhus.vance.brain.wowbagger.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Worker-type selection: light profiles run, spawnable engines are refused with the list of what does. */
class WowbaggerWorkerRegistryTest {

    private RecipeResolver recipes;
    private WowbaggerWorkerRegistry registry;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        recipes = mock(RecipeResolver.class);
        AiModelResolver models = mock(AiModelResolver.class);
        when(models.resolveOrDefault(any(), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved("openai", "cortecs", "deepseek-v4-flash", false));
        registry =
                new WowbaggerWorkerRegistry(List.of(new LightLlmWorker(mock(LightLlmService.class), models)), recipes);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("p1");
    }

    private void recipe(String name, String engine, boolean internal) {
        ResolvedRecipe recipe = mock(ResolvedRecipe.class);
        when(recipe.name()).thenReturn(name);
        when(recipe.engine()).thenReturn(engine);
        when(recipe.internal()).thenReturn(internal);
        when(recipe.params()).thenReturn(Map.of("model", "default:fast"));
        when(recipes.resolve(eq("acme"), eq("p1"), eq(name))).thenReturn(Optional.of(recipe));
    }

    @Test
    void internal_light_profile_becomes_an_llm_worker_with_its_model() {
        recipe("wowbagger-worker", "jeltz", true);

        PreparedWorker worker = registry.prepare(
                process, new WorkerSpec("wowbagger-worker", "classify", null, false, false, null, null, 3, null));

        assertThat(worker.kind()).isEqualTo(LightLlmWorker.KIND);
        assertThat(worker.recipeName()).isEqualTo("wowbagger-worker");
        assertThat(worker.resolvedModel()).isEqualTo("cortecs:deepseek-v4-flash");
    }

    @Test
    void spawnable_engine_recipe_is_refused_with_the_supported_types() {
        recipe("vogon-plan", "vogon", false);

        assertThatThrownBy(() -> registry.prepare(
                        process, new WorkerSpec("vogon-plan", "x", null, false, false, null, null, 3, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'vogon-plan' (engine vogon)")
                .hasMessageContaining("light workers only")
                .hasMessageContaining("llm (");
    }

    @Test
    void internal_profiles_of_claimed_engines_are_not_taken_by_the_llm_worker() {
        recipe("triage", "zem", true);

        assertThatThrownBy(() -> registry.prepare(
                        process, new WorkerSpec("triage", null, null, false, false, null, null, 3, null)))
                .hasMessageContaining("is not a Wowbagger worker");
    }

    @Test
    void missing_recipe_is_named() {
        when(recipes.resolve(any(), any(), eq("nope"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registry.prepare(
                        process, new WorkerSpec("nope", null, null, false, false, null, null, 3, null)))
                .hasMessageContaining("'nope' not found");
    }
}
