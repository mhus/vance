package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Focused tests for the four-spawn-path defaulting policy on
 * {@link RecipeResolver#applyDefaulting} (spec: {@code recipes.md}) and
 * the param-merge / locked-recipe rules on {@link RecipeResolver#apply}.
 */
class RecipeResolverTest {

    private RecipeLoader loader;
    private ThinkEngineService engineService;
    private ServerToolService serverToolService;
    private RecipeResolver resolver;

    @BeforeEach
    void setUp() {
        loader = mock(RecipeLoader.class);
        engineService = mock(ThinkEngineService.class);
        serverToolService = mock(ServerToolService.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<ThinkEngineService> engineProvider = mock(ObjectProvider.class);
        when(engineProvider.getObject()).thenReturn(engineService);

        // Default: every engine the recipe references resolves to a
        // ThinkEngine with no allowed-tools opinions.
        ThinkEngine fakeEngine = mock(ThinkEngine.class);
        when(fakeEngine.allowedTools()).thenReturn(Set.of());
        when(engineService.resolve(any())).thenReturn(Optional.of(fakeEngine));

        resolver = new RecipeResolver(loader, engineProvider, serverToolService);
    }

    // ──── applyDefaulting policy ─────────────────────────────────────────

    @Test
    void applyDefaulting_withRecipeName_resolvesThatRecipe() {
        stub("analyze");

        AppliedRecipe r = resolver.applyDefaulting(
                "acme", "proj", "analyze", null, null);

        assertThat(r.name()).isEqualTo("analyze");
        verify(loader).load("acme", "proj", "analyze");
    }

    @Test
    void applyDefaulting_withRecipeName_throws_whenRecipeMissing() {
        when(loader.load(any(), any(), eq("missing"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.applyDefaulting(
                "acme", "proj", "missing", null, null))
                .isInstanceOf(RecipeResolver.UnknownRecipeException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void applyDefaulting_withBlankRecipeName_resolvesDefaultRecipe() {
        stub("default");

        AppliedRecipe r = resolver.applyDefaulting(
                "acme", "proj", null, null, null);

        assertThat(r.name()).isEqualTo("default");
        verify(loader).load("acme", "proj", "default");
    }

    @Test
    void applyDefaulting_treatsBlankAsAbsent() {
        stub("default");

        AppliedRecipe r = resolver.applyDefaulting(
                "acme", "proj", "  ", null, null);

        assertThat(r.name()).isEqualTo("default");
    }

    // ──── param merge rules ──────────────────────────────────────────────

    @Test
    void apply_callerParams_overrideRecipeParams() {
        stubWithParams("analyze", Map.of("model", "default:fast", "temperature", 0.2));

        AppliedRecipe r = resolver.apply(
                "acme", "proj", "analyze", null,
                Map.of("temperature", 0.7));

        assertThat(r.params()).containsEntry("model", "default:fast");
        assertThat(r.params()).containsEntry("temperature", 0.7);
        assertThat(r.overriddenParamKeys()).containsExactly("temperature");
    }

    @Test
    void apply_lockedRecipe_ignoresCallerParams() {
        stubLocked("locked-recipe", Map.of("model", "default:fast"));

        AppliedRecipe r = resolver.apply(
                "acme", "proj", "locked-recipe", null,
                Map.of("model", "premium:slow", "temperature", 0.9));

        // Locked: caller params do not land on the result.
        assertThat(r.params()).containsEntry("model", "default:fast");
        assertThat(r.params()).doesNotContainKey("temperature");
        assertThat(r.overriddenParamKeys()).isEmpty();
    }

    @Test
    void apply_addsProgressLevelDefault_whenNotSet() {
        stubWithParams("analyze", Map.of("model", "x"));

        AppliedRecipe r = resolver.apply(
                "acme", "proj", "analyze", null, null);

        // The progress-level key (`tracing.progressLevel` etc.) gets a
        // default — engines never hard-code it.
        assertThat(r.params()).containsKey(
                de.mhus.vance.brain.progress.ProgressLevel.PARAM_KEY);
    }

    // ──── helpers ────────────────────────────────────────────────────────

    private void stub(String name) {
        stubWithParams(name, Map.of());
    }

    private void stubWithParams(String name, Map<String, Object> params) {
        when(loader.load(any(), any(), eq(name)))
                .thenReturn(Optional.of(recipe(name, params, false)));
    }

    private void stubLocked(String name, Map<String, Object> params) {
        when(loader.load(any(), any(), eq(name)))
                .thenReturn(Optional.of(recipe(name, params, true)));
    }

    private static ResolvedRecipe recipe(String name, Map<String, Object> params, boolean locked) {
        return new ResolvedRecipe(
                name,
                "test recipe",
                "arthur", // engine
                new LinkedHashMap<>(params),
                null, // promptPrefix
                PromptMode.APPEND,
                null, // dataRelayCorrection
                List.of(), List.of(),
                List.of(), // allowedToolsDefer
                new HashMap<>(), // modes (recipe-base)
                new HashMap<>(),
                List.of(),
                null,
                List.of(),
                locked,
                false, // internal
                List.of(),
                RecipeSource.RESOURCE);
    }
}
