package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
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
    private final ClientToolRegistry clientToolRegistry = mock(ClientToolRegistry.class);
    private final RecipeResolver resolver = new RecipeResolver(
            loader, engineSvcProvider, serverToolService, providerOf(clientToolRegistry));

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

    // ─────── Label expansion over client-registered tools ───────

    @Test
    void labelSelector_alsoExpandsToClientRegisteredToolsOfTheSession() {
        // A foot MCP pack (~/.vancetope/foot-tools/chrome.json) pushes its
        // labels on the ToolSpec; a recipe selects the capability by label
        // instead of naming 29 generated sub-tools.
        ResolvedRecipe r = recipe(
                List.of(), List.of("@browser"), List.of(), Map.of(), Map.of());
        when(loader.load(any(), any(), eq("coding"))).thenReturn(Optional.of(r));
        when(serverToolService.findByLabel(any(), any(), eq("browser"), any()))
                .thenReturn(List.of());
        when(clientToolRegistry.toolsFor("s1")).thenReturn(List.of(
                clientSpec("chrome__navigate_page", Set.of("browser", "mcp:chrome")),
                clientSpec("chrome__take_snapshot", Set.of("browser", "mcp:chrome")),
                clientSpec("client_file_read", Set.of("read-only"))));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "coding", "foot", ProcessMode.NORMAL,
                new de.mhus.vance.toolpack.ToolInvocationContext(
                        TENANT, PROJECT, "s1", "proc", "marvin"));

        assertThat(f.add()).containsExactlyInAnyOrder(
                "chrome__navigate_page", "chrome__take_snapshot");
    }

    @Test
    void labelSelector_withoutSessionScope_ignoresClientTools() {
        // The spawn path has no session: expanding client tool names there
        // would freeze a list that `/tools reload` invalidates.
        ResolvedRecipe r = recipe(
                List.of(), List.of("@browser"), List.of(), Map.of(), Map.of());
        when(loader.load(any(), any(), eq("coding"))).thenReturn(Optional.of(r));
        when(serverToolService.findByLabel(any(), any(), eq("browser"), any()))
                .thenReturn(List.of());

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "coding", "foot", ProcessMode.NORMAL);

        assertThat(f.add()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(clientToolRegistry);
    }

    @Test
    void labelSelector_unionsServerAndClientMatches_withoutDuplicates() {
        ResolvedRecipe r = recipe(
                List.of(), List.of("@browser"), List.of(), Map.of(), Map.of());
        when(loader.load(any(), any(), eq("coding"))).thenReturn(Optional.of(r));
        when(serverToolService.findByLabel(any(), any(), eq("browser"), any()))
                .thenReturn(List.of(stubTool("headless_fetch"), stubTool("shared_name")));
        when(clientToolRegistry.toolsFor("s1")).thenReturn(List.of(
                clientSpec("shared_name", Set.of("browser")),
                clientSpec("chrome__navigate_page", Set.of("browser"))));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "coding", "foot", ProcessMode.NORMAL,
                new de.mhus.vance.toolpack.ToolInvocationContext(
                        TENANT, PROJECT, "s1", "proc", "marvin"));

        assertThat(f.add()).containsExactlyInAnyOrder(
                "headless_fetch", "shared_name", "chrome__navigate_page");
    }

    private static de.mhus.vance.api.tools.ToolSpec clientSpec(
            String name, Set<String> labels) {
        de.mhus.vance.api.tools.ToolSpec spec = new de.mhus.vance.api.tools.ToolSpec();
        spec.setName(name);
        spec.setLabels(new java.util.LinkedHashSet<>(labels));
        return spec;
    }

    // ─────── Helpers ───────

    // ─────── Budget-priority hints (planning/tool-surface-budget.md) ───────

    @Test
    void priorityHints_areUnionedAcrossTheCascade_notFirstMatchWins() {
        // Visibility resolves first-match-wins, but keep/dropFirst carry
        // no visibility effect — two layers ranking different tools cannot
        // contradict each other, so both are honoured. Otherwise a mode
        // block that only ranks tools would silently drop the recipe's
        // own ranking.
        ResolvedRecipe r = recipeWithPriority(
                /*base keep*/ List.of("respond"),
                /*base dropFirst*/ List.of("gtd_*"),
                Map.of("foot", new ProfileBlock(
                        List.of(), List.of(), List.of(),
                        /*keep*/ List.of("process_spawn"),
                        /*dropFirst*/ List.of("kanban_*"),
                        Map.of("NORMAL", new RecipeModeBlock(
                                /*add*/ List.of("mode_add"),
                                /*remove*/ List.of(),
                                /*defer*/ List.of(),
                                /*keep*/ List.of("doc_read"),
                                /*dropFirst*/ List.of("sheet_*"))),
                        null, Map.of(), null)));
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.NORMAL);

        assertThat(f.add()).containsExactly("mode_add");
        assertThat(f.keep()).containsExactlyInAnyOrder(
                "respond", "process_spawn", "doc_read");
        assertThat(f.dropFirst()).containsExactlyInAnyOrder(
                "gtd_*", "kanban_*", "sheet_*");
    }

    @Test
    void priorityOnlyRecipe_stillYieldsAFilter() {
        // No visibility overlay anywhere — but the budget stage still has
        // something to go by, so this must not collapse to EMPTY.
        ResolvedRecipe r = recipeWithPriority(
                List.of("respond"), List.of("gtd_*"), Map.of());
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.NORMAL);

        assertThat(f.remove()).isEmpty();
        assertThat(f.add()).isEmpty();
        assertThat(f.defer()).isEmpty();
        assertThat(f.keep()).containsExactly("respond");
        assertThat(f.dropFirst()).containsExactly("gtd_*");
    }

    @Test
    void priorityOnlyModeBlock_doesNotShadowTheRecipeVisibilityLists() {
        // A mode block that only ranks tools must not win the visibility
        // lookup — otherwise it would hide the recipe's defer list.
        ResolvedRecipe r = recipeWithPriority(
                List.of(), List.of(),
                Map.of("foot", new ProfileBlock(
                        List.of(), List.of(), List.of("recipe_defer"),
                        List.of(), List.of(),
                        Map.of("NORMAL", new RecipeModeBlock(
                                /*add*/ List.of(), /*remove*/ List.of(),
                                /*defer*/ List.of(),
                                /*keep*/ List.of("doc_read"),
                                /*dropFirst*/ List.of())),
                        null, Map.of(), null)));
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.NORMAL);

        assertThat(f.defer()).containsExactly("recipe_defer");
        assertThat(f.keep()).containsExactly("doc_read");
    }

    @Test
    void priorityOnlyProfileModeBlock_doesNotShadowAnOuterModeBlock() {
        // The profile's mode block is present but visibility-empty (it only
        // ranks). Stopping the cascade there would drop the recipe-level
        // mode block's remove list — tools that should be gone stay primary.
        ResolvedRecipe r = new ResolvedRecipe(
                "arthur", "test recipe", "arthur", Map.of(),
                null, PromptMode.APPEND, null,
                /*add*/ List.of(), /*remove*/ List.of(), /*defer*/ List.of(),
                /*keep*/ List.of(), /*dropFirst*/ List.of(),
                /*modes*/ Map.of("NORMAL", new RecipeModeBlock(
                        List.of(), List.of("destructive_tool"), List.of())),
                /*profiles*/ Map.of("foot", new ProfileBlock(
                        List.of(), List.of(), List.of(),
                        List.of(), List.of(),
                        Map.of("NORMAL", new RecipeModeBlock(
                                /*add*/ List.of(), /*remove*/ List.of(),
                                /*defer*/ List.of(),
                                /*keep*/ List.of("doc_read"),
                                /*dropFirst*/ List.of())),
                        null, Map.of(), null)),
                List.of(), null, List.of(), false, false, false, null, List.of(),
                List.of(), RecipeSource.RESOURCE);
        when(loader.load(any(), any(), eq("arthur"))).thenReturn(Optional.of(r));

        RecipeResolver.ToolFilter f = resolver.toolFilterFor(
                TENANT, PROJECT, "arthur", "foot", ProcessMode.NORMAL);

        assertThat(f.remove()).containsExactly("destructive_tool");
        // The ranking of the shadowed-past block is still collected.
        assertThat(f.keep()).containsExactly("doc_read");
    }

    private static ResolvedRecipe recipeWithPriority(
            List<String> baseKeep,
            List<String> baseDropFirst,
            Map<String, ProfileBlock> profiles) {
        return new ResolvedRecipe(
                "arthur", "test recipe", "arthur", Map.of(),
                null, PromptMode.APPEND, null,
                /*add*/ List.of(), /*remove*/ List.of(), /*defer*/ List.of(),
                baseKeep, baseDropFirst,
                /*modes*/ Map.of(), profiles,
                List.of(), null, List.of(), false, false, false, null, List.of(),
                List.of(), RecipeSource.RESOURCE);
    }

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
