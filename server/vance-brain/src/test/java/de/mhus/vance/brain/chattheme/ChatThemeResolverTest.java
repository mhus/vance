package de.mhus.vance.brain.chattheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeProjectKind;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.tools.report.CssSanitizer;
import de.mhus.vance.brain.tools.report.CssScopePrefixer;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatThemeResolver} — the chat theme cascade: one theme per
 * session, every fallback converging on {@code default}. These tests
 * pin the resolution contract: hit by layer order, blank/invalid name
 * → default, miss → default with WARN, and the (internal-error) empty
 * answer when even the default is gone. The bundled
 * {@code default.css} itself is guarded by the smoke test at the
 * bottom — it must stay rule-free so "no theme" and "default theme"
 * render identically.
 */
class ChatThemeResolverTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";
    private static final String DEFAULT_PATH = "_vance/chat-themes/default.css";

    private final DocumentService documentService = mock(DocumentService.class);
    private final RecipeLoader recipeLoader = mock(RecipeLoader.class);
    private final ChatThemeResolver resolver = new ChatThemeResolver(documentService, recipeLoader);

    @Test
    void resolve_themeHit_returnsThemeContent() {
        when(documentService.lookupCascade(TENANT, PROJECT, "_vance/chat-themes/acme.css"))
                .thenReturn(hit("acme", "h1 { color: red; }"));

        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, "acme")).isEqualTo("h1 { color: red; }");
    }

    @Test
    void resolve_blankName_looksUpDefaultDirectly() {
        when(documentService.lookupCascade(TENANT, PROJECT, DEFAULT_PATH)).thenReturn(hit("default", "/* neutral */"));

        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, null)).isEqualTo("/* neutral */");
        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, "  ")).isEqualTo("/* neutral */");
        // No acme-shaped detour: blank means "no preference", not a
        // failed lookup, so only the default path is asked.
        verify(documentService, never()).lookupCascade(TENANT, PROJECT, "_vance/chat-themes/acme.css");
    }

    @Test
    void resolve_themeMiss_fallsBackToDefault() {
        when(documentService.lookupCascade(TENANT, PROJECT, "_vance/chat-themes/acme.css"))
                .thenReturn(Optional.empty());
        when(documentService.lookupCascade(TENANT, PROJECT, DEFAULT_PATH)).thenReturn(hit("default", "/* default */"));

        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, "acme")).isEqualTo("/* default */");
        verify(documentService).lookupCascade(TENANT, PROJECT, DEFAULT_PATH);
    }

    @Test
    void resolve_invalidName_usesDefault_neverBuildsRawPath() {
        when(documentService.lookupCascade(TENANT, PROJECT, DEFAULT_PATH)).thenReturn(hit("default", "/* default */"));

        // Traversal and case attempts all degrade to the default — the
        // cascade path is built from the validated name, never raw
        // input, and no path containing the raw fragment is ever asked.
        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, "../evil")).isEqualTo("/* default */");
        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, "Acme")).isEqualTo("/* default */");
        verify(documentService, never()).lookupCascade(TENANT, PROJECT, "_vance/chat-themes/../evil.css");
        verify(documentService, never()).lookupCascade(TENANT, PROJECT, "_vance/chat-themes/Acme.css");
    }

    @Test
    void resolve_missingBundledDefault_returnsEmpty() {
        // Both lookups empty — the bundled default is gone, an internal
        // misconfiguration. The answer is empty CSS (unstyled chat),
        // never an exception: a styling problem must not break a chat.
        when(documentService.lookupCascade(TENANT, PROJECT, DEFAULT_PATH)).thenReturn(Optional.empty());

        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, "acme")).isEmpty();
        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, null)).isEmpty();
    }

    @Test
    void resolve_explicitDefaultName_overridableViaCascade() {
        // "default" is a theme name like any other — a project layer
        // that ships its own default.css restyles every chat of the
        // scope without touching a recipe.
        when(documentService.lookupCascade(TENANT, PROJECT, DEFAULT_PATH))
                .thenReturn(hit("default", ".msg-user { border-left-color: red; }"));

        assertThat(resolver.resolveStylesheet(TENANT, PROJECT, "default"))
                .isEqualTo(".msg-user { border-left-color: red; }");
    }

    // ── effectiveThemeName (recipe → fetch name) ─────────────────────

    @Test
    void effectiveThemeName_noRecipe_defaultTheme() {
        assertThat(resolver.effectiveThemeName(TENANT, PROJECT, null)).isEqualTo("default");
        assertThat(resolver.effectiveThemeName(TENANT, PROJECT, "  ")).isEqualTo("default");
        verifyNoRecipeLoad();
    }

    @Test
    void effectiveThemeName_recipeWithWebTheme_themeName() {
        when(recipeLoader.load(TENANT, PROJECT, "acme")).thenReturn(Optional.of(recipeWithTheme("acme-dark")));

        assertThat(resolver.effectiveThemeName(TENANT, PROJECT, "acme")).isEqualTo("acme-dark");
    }

    @Test
    void effectiveThemeName_recipeWithoutWebTheme_defaultTheme() {
        when(recipeLoader.load(TENANT, PROJECT, "plain")).thenReturn(Optional.of(recipeWithTheme(null)));

        assertThat(resolver.effectiveThemeName(TENANT, PROJECT, "plain")).isEqualTo("default");
    }

    @Test
    void effectiveThemeName_recipeLoadFails_defaultTheme() {
        // A corrupted recipe must not break the session list or
        // bootstrap — the recipe loaded fine at spawn time, whatever
        // happened since is a styling question now.
        when(recipeLoader.load(TENANT, PROJECT, "broken"))
                .thenThrow(new IllegalStateException("recipe YAML is broken"));

        assertThat(resolver.effectiveThemeName(TENANT, PROJECT, "broken")).isEqualTo("default");
    }

    @Test
    void effectiveThemeName_unknownRecipe_defaultTheme() {
        when(recipeLoader.load(TENANT, PROJECT, "gone")).thenReturn(Optional.empty());

        assertThat(resolver.effectiveThemeName(TENANT, PROJECT, "gone")).isEqualTo("default");
    }

    private void verifyNoRecipeLoad() {
        verify(recipeLoader, never()).load(any(), any(), any());
    }

    private static ResolvedRecipe recipeWithTheme(@Nullable String webTheme) {
        return ResolvedRecipe.builder()
                .name("r")
                .description("d")
                .engine("arthur")
                .params(Map.of())
                .promptPrefix(null)
                .promptMode(PromptMode.APPEND)
                .dataRelayCorrection(null)
                .allowedToolsAdd(List.of())
                .allowedToolsRemove(List.of())
                .allowedToolsDefer(List.of())
                .allowedToolsKeep(List.of())
                .allowedToolsDropFirst(List.of())
                .modes(Map.of())
                .profiles(Map.of())
                .defaultActiveSkills(List.of())
                .allowedSkills(null)
                .triggerKeywords(List.of())
                .locked(false)
                .internal(false)
                .listed(false)
                .web(false)
                .projectKind(RecipeProjectKind.NORMAL)
                .title(null)
                .category(null)
                .webTheme(webTheme)
                .tags(List.of())
                .guards(List.of())
                .tenants(List.of())
                .source(RecipeSource.RESOURCE)
                .build();
    }

    /**
     * The bundled {@code default.css} is the normal state of every
     * chat session — it must be rule-free, and the serving pipeline
     * (sanitize + scope) must neither drop anything nor manufacture
     * rules out of its comments. This is the "no visible change"
     * guarantee of the whole feature, guarded as a smoke test the way
     * {@code ReportThemeResolverTest} guards the report default.
     */
    @Test
    void bundledDefault_isRuleFree_andSurvivesTheServingPipeline() throws Exception {
        String raw = new String(
                getClass()
                        .getResourceAsStream("/vance-defaults/_vance/chat-themes/default.css")
                        .readAllBytes(),
                StandardCharsets.UTF_8);

        // The file itself carries no rules (comments aside) — that is
        // what makes "default theme" a visual no-op.
        assertThat(stripComments(raw)).doesNotContain("{");

        // The pipeline must keep it that way: no scope prefix appears,
        // because there is no rule to prefix.
        String served = CssScopePrefixer.scope(CssSanitizer.sanitize(raw), ChatThemeController.SCOPE_CLASS);
        assertThat(stripComments(served)).doesNotContain("{");
        assertThat(served).doesNotContain(ChatThemeController.SCOPE_CLASS + ChatThemeController.SCOPE_CLASS);
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static Optional<LookupResult> hit(String name, String content) {
        return Optional.of(new LookupResult(
                ChatThemeResolver.THEME_PATH_PREFIX + name + ".css", content, LookupResult.Source.PROJECT, null));
    }
}
