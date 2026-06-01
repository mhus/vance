package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the bundled {@code vance-defaults/recipes/python.yaml} through
 * the real {@link RecipeLoader} (with a mocked
 * {@link DocumentService}) so we catch YAML structural drift,
 * Pebble compile errors in {@code promptPrefix}, and silent
 * regressions in {@code allowedToolsAdd}.
 */
class PythonRecipeTest {

    private static final String RECIPE_NAME = "python";
    private static final String RECIPE_PATH = "_vance/recipes/python.yaml";

    private DocumentService documentService;
    private RecipeLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        documentService = mock(DocumentService.class);
        loader = new RecipeLoader(documentService, new PromptTemplateRenderer());

        String content = new String(
                new ClassPathResource("vance-defaults/recipes/python.yaml")
                        .getInputStream()
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        when(documentService.lookupCascade(any(), any(), eq(RECIPE_PATH)))
                .thenReturn(Optional.of(new LookupResult(
                        RECIPE_PATH, content, LookupResult.Source.RESOURCE, null)));
    }

    @Test
    void load_parsesYaml() {
        Optional<ResolvedRecipe> r = loader.load("acme", "proj", RECIPE_NAME);

        assertThat(r).isPresent();
        assertThat(r.get().name()).isEqualTo(RECIPE_NAME);
        assertThat(r.get().engine()).isEqualTo("ford");
        assertThat(r.get().description()).contains("Python worker");
    }

    @Test
    void load_promotesAllPythonToolsToPrimary() {
        ResolvedRecipe r = loader.load("acme", "proj", RECIPE_NAME).orElseThrow();

        assertThat(r.allowedToolsAdd())
                .containsExactlyInAnyOrder(
                        "python_create",
                        "python_install",
                        "python_uninstall",
                        "python_run",
                        "python_set_interpreter");
        assertThat(r.allowedToolsRemove()).isEmpty();
    }

    @Test
    void load_paramsCarryCodeModel() {
        ResolvedRecipe r = loader.load("acme", "proj", RECIPE_NAME).orElseThrow();

        Map<String, Object> params = r.params();
        assertThat(params).containsEntry("model", "default:code");
        assertThat(params).containsEntry("validation", true);
        assertThat(params).containsKey("maxIterations");
    }

    @Test
    void load_promptPrefixCoversTheTypicalSequence() {
        ResolvedRecipe r = loader.load("acme", "proj", RECIPE_NAME).orElseThrow();
        PromptTemplateRenderer renderer = new PromptTemplateRenderer();

        // The prompt is no longer dual-branched on has_python_rootdir
        // (python_create is idempotent now), so a single render with
        // an empty context must cover all four numbered steps.
        String rendered = renderer.render(r.promptPrefix(), Map.of());

        assertThat(rendered)
                .contains("python_create")
                .contains("python_install")
                .contains("python_run")
                .contains("idempotent")
                .contains("respond");
    }

    @Test
    void load_profilesExist() {
        ResolvedRecipe r = loader.load("acme", "proj", RECIPE_NAME).orElseThrow();

        assertThat(r.profiles())
                .containsKeys("foot", "web", "default");
        // Web/default strip the client_* surface; foot keeps it.
        assertThat(r.profiles().get("web").allowedToolsRemove())
                .contains("client_file_read", "client_exec_run");
        assertThat(r.profiles().get("foot").allowedToolsRemove())
                .isNullOrEmpty();
    }

    @Test
    void load_tagsContainPython() {
        ResolvedRecipe r = loader.load("acme", "proj", RECIPE_NAME).orElseThrow();
        assertThat(r.tags()).contains("python");
    }
}
