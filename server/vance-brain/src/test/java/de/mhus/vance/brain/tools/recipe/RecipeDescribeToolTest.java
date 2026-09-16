package de.mhus.vance.brain.tools.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.recipe.GuardConfig;
import de.mhus.vance.brain.recipe.GuardPoint;
import de.mhus.vance.brain.recipe.ProfileBlock;
import de.mhus.vance.brain.recipe.RecipeModeBlock;
import de.mhus.vance.brain.recipe.RecipeProjectKind;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link RecipeDescribeTool} — the completeness contract: the tool's
 * output must carry <b>every</b> parsed recipe field. A project-level
 * override (the chat-theme workflow is the driver) replaces the whole
 * recipe file, and a field this tool does not show would be silently
 * lost in the reconstruction — so each new {@link ResolvedRecipe}
 * component has to appear here. The bottom test is the guard: a
 * fully-populated recipe must surface every key.
 */
class RecipeDescribeToolTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";

    @Test
    void describe_unknownRecipe_throws() {
        RecipeResolver resolver = mock(RecipeResolver.class);
        when(resolver.resolve(TENANT, PROJECT, "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new RecipeDescribeTool(resolver).invoke(Map.of("name", "missing"), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void describe_minimalRecipe_showsCoreFieldsOnly() {
        ResolvedRecipe minimal = ResolvedRecipe.builder()
                .name("plain")
                .description("Plain recipe")
                .engine("arthur")
                .params(Map.of())
                .promptPrefix(null)
                .promptMode(PromptMode.APPEND)
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
                .guards(List.of())
                .tags(List.of())
                .tenants(List.of())
                .projectKind(RecipeProjectKind.NORMAL)
                .source(RecipeSource.RESOURCE)
                .build();

        Map<String, Object> out = describeWith(minimal);

        // Always-on core; nothing else — an empty/absent field must not
        // appear as noise the caller could mistake for a set value.
        assertThat(out.keySet()).containsExactlyInAnyOrder("name", "description", "engine", "source", "params");
    }

    @Test
    void describe_fullyPopulatedRecipe_showsEveryField() {
        ResolvedRecipe full = ResolvedRecipe.builder()
                .name("full")
                .description("Every field set")
                .engine("ford")
                .params(Map.of("model", "x"))
                .promptPrefix("persona…")
                .promptMode(PromptMode.OVERWRITE)
                .dataRelayCorrection("fixed text")
                .allowedToolsAdd(List.of("doc_write"))
                .allowedToolsRemove(List.of("exec_run"))
                .allowedToolsDefer(List.of("bulk_tool"))
                .allowedToolsKeep(List.of("important_tool"))
                .allowedToolsDropFirst(List.of("minor_tool"))
                .modes(Map.of(
                        "NORMAL", new RecipeModeBlock(List.of("doc_read"), List.of(), List.of(), List.of(), List.of())))
                .profiles(Map.of(
                        "laptop",
                        new ProfileBlock(
                                List.of("tool_a"),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                Map.of(),
                                "laptop prompt",
                                Map.of("param", "value"),
                                null)))
                .defaultActiveSkills(List.of("skill-y"))
                .allowedSkills(List.of("skill-y", "skill-z"))
                .triggerKeywords(List.of("analyse"))
                .locked(true)
                .internal(false)
                .listed(true)
                .web(true)
                .projectKind(RecipeProjectKind.ANY)
                .title("Full Recipe")
                .category("workers")
                .webTheme("acme")
                .tags(List.of("research"))
                .guards(List.of(new GuardConfig("script.js", null, Map.of(), false, GuardPoint.STOP, 3)))
                .tenants(List.of("acme"))
                .source(RecipeSource.PROJECT)
                .build();

        Map<String, Object> out = describeWith(full);

        // The completeness contract — see the class comment. When a new
        // ResolvedRecipe component is added, this line fails until the
        // tool shows it (that failure is the point).
        assertThat(out.keySet())
                .containsExactlyInAnyOrder(
                        "name",
                        "description",
                        "engine",
                        "source",
                        "params",
                        "promptPrefix",
                        "promptMode",
                        "dataRelayCorrection",
                        "allowedToolsAdd",
                        "allowedToolsRemove",
                        "allowedToolsDefer",
                        "allowedToolsKeep",
                        "allowedToolsDropFirst",
                        "modes",
                        "profiles",
                        "defaultActiveSkills",
                        "allowedSkills",
                        "triggerKeywords",
                        "guards",
                        "tenants",
                        "title",
                        "category",
                        "webTheme",
                        "projectKind",
                        "listed",
                        "web",
                        "locked",
                        "tags");
    }

    private Map<String, Object> describeWith(ResolvedRecipe recipe) {
        RecipeResolver resolver = mock(RecipeResolver.class);
        when(resolver.resolve(TENANT, PROJECT, recipe.name())).thenReturn(Optional.of(recipe));
        return new RecipeDescribeTool(resolver).invoke(Map.of("name", recipe.name()), ctx());
    }

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext(TENANT, PROJECT, null, null, "alice");
    }
}
