package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fail-fast parsing of RecipeLoader. A recipe is loaded lazily on first use;
 * the loader must reject a malformed recipe with {@link RecipeLoader.RecipeParseException}
 * rather than return a silently-broken recipe (missing engine → spawn with no
 * algorithm; bad prompt template → every turn fails later). Pins the required
 * fields, the type guards, and the compile-time template validation.
 */
class RecipeLoaderTest {

    private DocumentService documentService;
    private PromptTemplateRenderer renderer;
    private RecipeLoader loader;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        renderer = mock(PromptTemplateRenderer.class);
        loader = new RecipeLoader(documentService, renderer);
    }

    @Test
    void load_blankName_returnsEmpty() {
        assertThat(loader.load("acme", "p-1", "  ")).isEmpty();
    }

    @Test
    void load_noCascadeHit_returnsEmpty() {
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.empty());
        assertThat(loader.load("acme", "p-1", "analyze")).isEmpty();
    }

    @Test
    void load_validRecipe_isResolved() {
        stubRecipe("""
                description: Analyse a topic
                engine: eddie
                """);

        ResolvedRecipe recipe = loader.load("acme", "p-1", "analyze").orElseThrow();

        assertThat(recipe.name()).isEqualTo("analyze");
        assertThat(recipe.engine()).isEqualTo("eddie");
        assertThat(recipe.description()).isEqualTo("Analyse a topic");
    }

    @Test
    void load_nonMapYaml_failsFast() {
        stubRecipe("just a scalar");
        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("top-level map");
    }

    @Test
    void load_missingDescription_failsFast() {
        stubRecipe("engine: eddie\n");
        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("description");
    }

    @Test
    void load_missingEngine_failsFast() {
        stubRecipe("description: no engine here\n");
        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("engine");
    }

    @Test
    void load_paramsNotMap_failsFast() {
        stubRecipe("""
                description: bad params
                engine: eddie
                params: not-a-map
                """);
        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("params");
    }

    @Test
    void load_invalidPromptTemplate_failsFast() {
        stubRecipe("""
                description: bad template
                engine: eddie
                promptPrefix: "{% broken"
                """);
        doThrow(new PromptTemplateException("syntax error", null))
                .when(renderer).compile("{% broken");

        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("promptPrefix");
    }

    // ── helpers ──────────────────────────────────────────────────

    private void stubRecipe(String yaml) {
        LookupResult hit = new LookupResult(
                RecipeLoader.RECIPE_PATH_PREFIX + "analyze" + RecipeLoader.RECIPE_PATH_SUFFIX,
                yaml, LookupResult.Source.VANCE, null);
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(hit));
    }
}
