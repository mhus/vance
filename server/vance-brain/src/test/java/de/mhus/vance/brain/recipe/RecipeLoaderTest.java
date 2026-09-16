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
    void load_guardBlock_parsesAllPoints() {
        stubRecipe("""
                description: Guards at every point
                engine: eddie
                guard:
                  - script: _vance/guards/start.js
                    trigger: start
                  - script: _vance/guards/safety.js
                    trigger: command
                    params: { judge: "safe?" }
                  - scriptBody: vance.guard.continueWith('nudge');
                    trigger: both
                    maxRounds: 4
                """);

        ResolvedRecipe recipe = loader.load("acme", "p-1", "analyze").orElseThrow();

        assertThat(recipe.guards())
                .extracting(GuardConfig::trigger)
                .containsExactly(GuardPoint.START, GuardPoint.COMMAND, GuardPoint.BOTH);
        assertThat(recipe.guards().get(0).maxRounds()).isEqualTo(2); // default
        assertThat(recipe.guards().get(1).params()).containsEntry("judge", "safe?");
        assertThat(recipe.guards().get(2).maxRounds()).isEqualTo(4);
    }

    @Test
    void load_guardBlock_unknownTrigger_isRejected() {
        stubRecipe("""
                description: Bad trigger
                engine: eddie
                guard:
                  - script: _vance/guards/x.js
                    trigger: yield
                """);

        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("trigger");
    }

    @Test
    void load_promptPrefixUnderParams_isNotAPrompt() {
        // The shape that cost coding.yaml and trillian-worker-void.yaml their
        // entire prompt: indented one level too far, accepted in silence.
        stubRecipe("""
                description: Worker
                engine: frankie
                params:
                  promptPrefix: |
                    You must call trillian_done when finished.
                """);

        ResolvedRecipe recipe = loader.load("acme", "p-1", "worker").orElseThrow();

        assertThat(recipe.promptPrefix()).isNull();
        assertThat(recipe.params()).containsKey("promptPrefix");
    }

    @Test
    void load_promptPrefixAtTopLevel_isThePrompt() {
        stubRecipe("""
                description: Worker
                engine: frankie
                promptPrefix: |
                  You must call trillian_done when finished.
                params:
                  model: default:fast
                """);

        ResolvedRecipe recipe = loader.load("acme", "p-1", "worker").orElseThrow();

        assertThat(recipe.promptPrefix()).contains("trillian_done");
        assertThat(recipe.params()).doesNotContainKey("promptPrefix");
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
    void load_webTheme_valid_isResolved() {
        stubRecipe("""
                description: Themed chat worker
                engine: arthur
                webTheme: acme-dark
                """);

        assertThat(loader.load("acme", "p-1", "analyze"))
                .hasValueSatisfying(r -> assertThat(r.webTheme()).isEqualTo("acme-dark"));
    }

    @Test
    void load_webTheme_absent_isNull() {
        stubRecipe("""
                description: Plain worker
                engine: arthur
                """);

        assertThat(loader.load("acme", "p-1", "analyze"))
                .hasValueSatisfying(r -> assertThat(r.webTheme()).isNull());
    }

    @Test
    void load_webTheme_blank_isNull() {
        stubRecipe("""
                description: Blank theme worker
                engine: arthur
                webTheme: "  "
                """);

        assertThat(loader.load("acme", "p-1", "analyze"))
                .hasValueSatisfying(r -> assertThat(r.webTheme()).isNull());
    }

    @Test
    void load_webTheme_invalid_failsFast() {
        // The name becomes a path segment of the chat-theme endpoint —
        // a typo is a load-time error, not a silently never-applying
        // theme (existence, however, is runtime fail-open).
        stubRecipe("""
                description: Traversal attempt
                engine: arthur
                webTheme: "../evil"
                """);

        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("webTheme");
    }

    @Test
    void load_invalidPromptTemplate_failsFast() {
        stubRecipe("""
                description: bad template
                engine: eddie
                promptPrefix: "{% broken"
                """);
        doThrow(new PromptTemplateException("syntax error", null))
                .when(renderer)
                .compile("{% broken");

        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("promptPrefix");
    }

    // ── helpers ──────────────────────────────────────────────────

    // ─── tenants: the load-time gate ────────────────────────────────

    @Test
    void load_withoutTenants_appliesEverywhere() {
        stubRecipe("""
                description: Analyse a topic
                engine: eddie
                """);

        // The default that keeps every recipe written before the field alive.
        assertThat(loader.load("acme", "p-1", "analyze")).isPresent();
        assertThat(loader.load("_vance", "p-1", "analyze")).isPresent();
    }

    @Test
    void load_forAnotherTenant_returnsEmptyRatherThanTheRecipe() {
        stubRecipe("""
                description: Funkwill
                engine: trillian-user
                tenants: [_vance]
                """);

        // Empty, not a refusal with its own wording: a caller must not be
        // able to tell "exists but not for you" from "does not exist", or
        // the endpoint becomes an oracle over other tenants' recipes.
        assertThat(loader.load("acme", "p-1", "analyze")).isEmpty();
    }

    @Test
    void load_forItsOwnTenant_isResolved() {
        stubRecipe("""
                description: Funkwill
                engine: trillian-user
                tenants: [_vance]
                """);

        ResolvedRecipe recipe = loader.load("_vance", "p-1", "analyze").orElseThrow();

        assertThat(recipe.tenants()).containsExactly("_vance");
    }

    @Test
    void load_tenantsMatchIgnoresCaseAndSurroundingSpace() {
        // The configured side is hand-written YAML; the asked-for id comes
        // from the system and is taken as it is.
        stubRecipe("""
                description: Funkwill
                engine: trillian-user
                tenants: [ "  _Vance  " ]
                """);

        assertThat(loader.load("_vance", "p-1", "analyze")).isPresent();
    }

    @Test
    void load_severalTenants_anyOfThemPasses() {
        stubRecipe("""
                description: Funkwill
                engine: trillian-user
                tenants: [_vance, acme]
                """);

        assertThat(loader.load("acme", "p-1", "analyze")).isPresent();
        assertThat(loader.load("other", "p-1", "analyze")).isEmpty();
    }

    @Test
    void appliesTo_emptyTenantsIsEveryone_evenWithoutATenantId() {
        stubRecipe("""
                description: Analyse a topic
                engine: eddie
                """);
        ResolvedRecipe open = loader.load("acme", "p-1", "analyze").orElseThrow();

        assertThat(open.appliesTo(null)).isTrue();
    }

    @Test
    void appliesTo_restrictedRecipeRefusesAnAbsentTenant() {
        stubRecipe("""
                description: Funkwill
                engine: trillian-user
                tenants: [_vance]
                """);
        ResolvedRecipe restricted = loader.load("_vance", "p-1", "analyze").orElseThrow();

        // "I do not know which tenant" is not a reason to hand out a recipe
        // that named one.
        assertThat(restricted.appliesTo(null)).isFalse();
    }

    @Test
    void load_category_isNormalizedToKebabKey() {
        stubRecipe("""
                description: Analyse a topic
                engine: eddie
                category:   Coding
                """);

        ResolvedRecipe recipe = loader.load("acme", "p-1", "analyze").orElseThrow();

        // Hand-written YAML on both sides of the match — the recipe field and
        // the ids of _vance/config/recipe_categories.yaml are normalised the
        // same way, so 'Coding' groups with a documented 'coding'.
        assertThat(recipe.category()).isEqualTo("coding");
    }

    @Test
    void load_blankCategory_isTreatedAsAbsent() {
        stubRecipe("""
                description: Analyse a topic
                engine: eddie
                category: "   "
                """);

        ResolvedRecipe recipe = loader.load("acme", "p-1", "analyze").orElseThrow();

        assertThat(recipe.category()).isNull();
    }

    @Test
    void load_nonStringCategory_isRejected() {
        stubRecipe("""
                description: Analyse a topic
                engine: eddie
                category: 42
                """);

        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("'category' must be a string");
    }

    @Test
    void load_projectKind_defaultsToNormal() {
        stubRecipe("""
                description: Analyse a topic
                engine: ford
                """);

        // Absent field = regular-project recipe. Every recipe written
        // before projectKind existed must keep its old picker behaviour.
        assertThat(loader.load("acme", "p-1", "analyze").orElseThrow().projectKind())
                .isEqualTo(RecipeProjectKind.NORMAL);
    }

    @Test
    void load_projectKind_isParsed() {
        stubRecipe("""
                description: Hub chat
                engine: eddie
                projectKind: system
                """);

        assertThat(loader.load("acme", "p-1", "analyze").orElseThrow().projectKind())
                .isEqualTo(RecipeProjectKind.SYSTEM);
    }

    @Test
    void load_unknownProjectKind_isRejected() {
        stubRecipe("""
                description: Analyse a topic
                engine: ford
                projectKind: hub
                """);

        // A typo must fail the recipe load, not the picker: silently
        // defaulting would hide the recipe from (or leak it into) a
        // whole picker without any error anywhere.
        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("unknown projectKind 'hub'")
                .hasMessageContaining("NORMAL, SYSTEM or ANY");
    }

    @Test
    void load_nonStringProjectKind_isRejected() {
        stubRecipe("""
                description: Analyse a topic
                engine: ford
                projectKind: 42
                """);

        assertThatThrownBy(() -> loader.load("acme", "p-1", "analyze"))
                .isInstanceOf(RecipeLoader.RecipeParseException.class)
                .hasMessageContaining("'projectKind' must be a string");
    }

    private void stubRecipe(String yaml) {
        LookupResult hit = new LookupResult(
                RecipeLoader.RECIPE_PATH_PREFIX + "analyze" + RecipeLoader.RECIPE_PATH_SUFFIX,
                yaml,
                LookupResult.Source.VANCE,
                null);
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(hit));
    }
}
