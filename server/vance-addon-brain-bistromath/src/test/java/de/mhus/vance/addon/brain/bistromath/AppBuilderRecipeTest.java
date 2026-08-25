package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.tools.worktarget.BaseEngineTools;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bundled {@code app-builder} recipe, loaded through the real
 * {@link RecipeLoader} — so YAML drift and a Pebble error in
 * {@code promptPrefix} fail here rather than on someone's first turn.
 *
 * <p>Two of these assertions are the recipe's reason to exist. The manual
 * paths put {@code _vance/manuals/bistromath/} first, which is what lets the
 * hooks inside those manuals resolve by their short name; and every
 * {@code manual_read('…')} in the prompt has to name a manual this addon
 * actually ships, because a hook onto a name its reader cannot find is worse
 * than no hook — the agent looks, misses, and improvises the schema.
 */
class AppBuilderRecipeTest {

    private static final String RECIPE_NAME = "app-builder";
    private static final String RECIPE_PATH = "_vance/recipes/app-builder.yaml";
    private static final String RESOURCE = "vance-defaults/" + RECIPE_PATH;
    private static final String MANUAL_DIR = "vance-defaults/_vance/manuals/bistromath/";

    private RecipeLoader loader;

    @BeforeEach
    void setUp() {
        DocumentService documentService = mock(DocumentService.class);
        loader = new RecipeLoader(documentService, new PromptTemplateRenderer());
        when(documentService.lookupCascade(any(), any(), eq(RECIPE_PATH)))
                .thenReturn(Optional.of(new LookupResult(
                        RECIPE_PATH, resource(RESOURCE), LookupResult.Source.RESOURCE, null)));
    }

    @Test
    void recipe_parsesAndIsOfferedInThePicker() {
        ResolvedRecipe r = load();

        assertThat(r.engine()).isEqualTo("frankie");
        assertThat(r.listed()).isTrue();
        assertThat(r.internal()).isFalse();
        assertThat(r.title()).isEqualTo("App Builder — Custom Applications");
    }

    /**
     * The craft manuals come first. Reverse the order and every hook inside
     * them (`manual_read('views')`) still resolves — against the global
     * manual folder, where no such file exists.
     */
    @Test
    void manualPaths_putTheCraftManualsFirst() {
        Object paths = load().params().get("manualPaths");

        assertThat(paths).isInstanceOf(List.class);
        List<String> ordered = ((List<?>) paths).stream().map(String::valueOf).toList();
        assertThat(ordered).containsExactly(
                "_vance/manuals/bistromath/",
                "_vance/manuals/");
    }

    /**
     * Every hook in the prompt names a manual that ships with this addon.
     *
     * <p>Grows with the manual set on its own: rename a file and this fails.
     */
    @Test
    void promptHooks_nameManualsThisAddonShips() {
        Matcher m = Pattern.compile("manual_read\\('([^']+)'\\)")
                .matcher(String.valueOf(load().promptPrefix()));

        Set<String> hooked = new TreeSet<>();
        while (m.find()) {
            hooked.add(m.group(1));
        }

        assertThat(hooked).contains("overview", "hello-world");
        for (String name : hooked) {
            assertThat(AppBuilderRecipeTest.class.getClassLoader()
                    .getResource(MANUAL_DIR + name + ".md"))
                    .as("manual bundled for hook manual_read('%s')", name)
                    .isNotNull();
        }
    }

    /**
     * No file and no shell tools — a custom application has neither.
     *
     * <p>Asserted against the family itself, not a copy of it: when
     * {@link BaseEngineTools#WORK_TARGET} grows a tool, Frankie's baseline
     * hands it to this worker, and the failure is quiet — a `file_write` of
     * `main.js` into a workspace succeeds and the app never changes.
     */
    @Test
    void theWholeWorkTargetFamilyIsRemoved() {
        assertThat(load().allowedToolsRemove())
                .containsAll(BaseEngineTools.WORK_TARGET);
    }

    /** Frankie carries no document tools, so the working set is named here. */
    @Test
    void documentAndAppToolsArePrimary() {
        assertThat(load().allowedToolsAdd()).contains(
                "bistromath_app_create", "app_rebuild", "kind_validate",
                "doc_read", "doc_write", "doc_edit", "doc_list_in_folder");
    }

    /**
     * The kind families are reachable but deferred: an app edits `records` /
     * `sheet` / `list` / `tree` documents as structures, and so may the agent
     * — but as primaries that is ~40 schemas in every turn for a capability
     * most turns never touch.
     */
    @Test
    void kindToolFamiliesAreDeferredNotPrimary() {
        ResolvedRecipe r = load();

        assertThat(r.allowedToolsDefer()).contains(
                "@kind-records", "@kind-sheet", "@kind-list", "@kind-tree");
        assertThat(r.allowedToolsAdd()).noneMatch(t -> t.startsWith("@kind-"));
    }

    private ResolvedRecipe load() {
        return loader.load("acme", "proj", RECIPE_NAME).orElseThrow();
    }

    private static String resource(String path) {
        try (InputStream in =
                     AppBuilderRecipeTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("not on the classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
