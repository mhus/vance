package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.toolpack.Tool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Cascade tests for {@link RecipeResolver#toolFilterFor}. Verifies §14.4
 * lookup order:
 *
 * <pre>
 *  profiles[profile].modes[mode] →
 *  profiles[profile].modes["default"] →
 *  profiles[profile] (profile-base) →
 *  profiles["default"] →
 *  recipe.modes[mode] →
 *  recipe-base.
 * </pre>
 *
 * <p>And §14.1: {@code @<label>}-selectors are expanded against
 * {@link ServerToolService#findByLabel}; literal entries pass through.
 */
class RecipeResolverModeFilterTest {

    private final RecipeLoader loader = mock(RecipeLoader.class);
    private final ObjectProvider<ThinkEngineService> engineSvcProvider = providerOf(null);
    private final ServerToolService serverToolService = mock(ServerToolService.class);
    private final RecipeResolver resolver = new RecipeResolver(
            loader, engineSvcProvider, serverToolService);

    private static final String TENANT = "acme";
    private static final String PROJECT = "p1";

    // ─────── Cascade ───────

    @Test
    void modeBlock_inProfile_winsOverProfileBase_andRecipeBase() {
        ResolvedRecipe r = recipe(
                /*recipe-base remove*/ List.of("recipe_base_remove"),
                /*recipe-base add*/    List.of(),
                /*recipe-base defer*/  List.of(),
                /*recipe-base modes*/  Map.of(),
                /*profiles*/ Map.of("foot", new ProfileBlock(
                        /*add*/ List.of(),
                        /*remove*/ List.of("profile_base_remove"),
                        /*defer*/ List.of(),
                        /*modes*/ Map.of("EXPLORING", new RecipeModeBlock(
                                /*add*/ List.of(),
                                /*remove*/ List.of("mode_remove"),
                                /*defer*/ List.of())),
                        /*promptPrefixAppend*/ null,
                        Map.of(),
                        null)));
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.EXPLORING);

        // override semantics — only mode_remove, NOT profile_base_remove or recipe_base_remove
        assertThat(f.remove()).containsExactly("mode_remove");
        assertThat(f.add()).isEmpty();
        assertThat(f.defer()).isEmpty();
    }

    @Test
    void noModeBlock_fallsThroughToProfileBase() {
        ResolvedRecipe r = recipe(
                List.of(), List.of(), List.of(), Map.of(),
                Map.of("foot", new ProfileBlock(
                        List.of("profile_add"),
                        List.of(),
                        List.of("profile_defer"),
                        Map.of(),
                        null,
                        Map.of(),
                        null)));
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.EXPLORING);

        assertThat(f.add()).containsExactly("profile_add");
        assertThat(f.defer()).containsExactly("profile_defer");
    }

    @Test
    void noProfileMatch_usesDefaultProfileMode() {
        ProfileBlock defaultProfile = new ProfileBlock(
                List.of(), List.of(), List.of(),
                Map.of("EXPLORING", new RecipeModeBlock(
                        List.of(), List.of("default_mode_remove"), List.of())),
                null, Map.of(), null);
        ResolvedRecipe r = recipe(
                List.of(), List.of(), List.of(), Map.of(),
                Map.of("default", defaultProfile));
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        // unknown profile "web" → fall through to default profile
        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "web", ProcessMode.EXPLORING);

        assertThat(f.remove()).containsExactly("default_mode_remove");
    }

    @Test
    void noProfileBlocks_usesRecipeBaseModes() {
        ResolvedRecipe r = recipe(
                List.of(), List.of(), List.of(),
                Map.of("EXPLORING", new RecipeModeBlock(
                        List.of(), List.of("base_mode_remove"), List.of())),
                Map.of());
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.EXPLORING);

        assertThat(f.remove()).containsExactly("base_mode_remove");
    }

    @Test
    void noModesAtAll_andNoProfile_fallsThroughToRecipeBase() {
        ResolvedRecipe r = recipe(
                List.of("base_remove"),
                List.of("base_add"),
                List.of("base_defer"),
                Map.of(),
                Map.of());
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.NORMAL);

        assertThat(f.remove()).containsExactly("base_remove");
        assertThat(f.add()).containsExactly("base_add");
        assertThat(f.defer()).containsExactly("base_defer");
    }

    @Test
    void unknownRecipe_returnsEmptyFilter() {
        when(loader.load(any(), any(), any())).thenReturn(Optional.empty());

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "missing", "foot", ProcessMode.EXPLORING);

        assertThat(f).isSameAs(RecipeResolver.ToolFilter.EMPTY);
    }

    @Test
    void modeDefaultKey_isCatchAllForProfileModes() {
        ProfileBlock fp = new ProfileBlock(
                List.of(), List.of(), List.of(),
                Map.of("default", new RecipeModeBlock(
                        List.of(), List.of("catchall_remove"), List.of())),
                null, Map.of(), null);
        ResolvedRecipe r = recipe(
                List.of(), List.of(), List.of(), Map.of(),
                Map.of("foot", fp));
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        // EXECUTING isn't listed explicitly → "default" mode-block matches
        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.EXECUTING);

        assertThat(f.remove()).containsExactly("catchall_remove");
    }

    // ─────── Label expansion ───────

    @Test
    void labelSelector_expandsToConcreteToolNames() {
        ResolvedRecipe r = recipe(
                List.of(), List.of(), List.of(),
                Map.of("EXPLORING", new RecipeModeBlock(
                        List.of(),
                        List.of("@write", "literal_tool"),
                        List.of())),
                Map.of());
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));
        when(serverToolService.findByLabel(eq(TENANT), any(), eq("write"), any()))
                .thenReturn(List.of(stubTool("doc_edit"), stubTool("doc_delete")));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.EXPLORING);

        assertThat(f.remove()).containsExactlyInAnyOrder(
                "doc_edit", "doc_delete", "literal_tool");
    }

    @Test
    void unresolvedLabel_silentlyExpandsToEmpty() {
        ResolvedRecipe r = recipe(
                List.of(), List.of(), List.of(),
                Map.of("EXPLORING", new RecipeModeBlock(
                        List.of(),
                        List.of("@nonsense"),
                        List.of())),
                Map.of());
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));
        when(serverToolService.findByLabel(any(), any(), eq("nonsense"), any()))
                .thenReturn(List.of());

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.EXPLORING);

        assertThat(f.remove()).isEmpty();
    }

    // ─────── Helpers ───────

    private static ResolvedRecipe recipe(
            List<String> baseRemove,
            List<String> baseAdd,
            List<String> baseDefer,
            Map<String, RecipeModeBlock> baseModes,
            Map<String, ProfileBlock> profiles) {
        return new ResolvedRecipe(
                "arthur",
                "test recipe",
                "arthur",
                Map.of(),
                null, PromptMode.APPEND, null,
                baseAdd, baseRemove, baseDefer, baseModes, profiles,
                List.of(), null, List.of(), false, false, false, null, List.of(), null, RecipeSource.RESOURCE);
    }

    private static Tool stubTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public boolean primary() { return true; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Set<String> labels() { return Set.of(); }
            @Override public Map<String, Object> invoke(Map<String, Object> p,
                    de.mhus.vance.toolpack.ToolInvocationContext ctx) {
                return Map.of();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        when(p.getObject()).thenReturn(value);
        return p;
    }
}
