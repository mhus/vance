package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.recipe.RecipeListedDto;
import de.mhus.vance.api.recipe.RecipeListedResponse;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The project-kind filter of the listed-recipes endpoint: a recipe marked
 * {@code projectKind: system} (Eddie) shows only in SYSTEM hub projects,
 * {@code normal} recipes only in regular ones. Without the filter the
 * picker lies twice — Arthur in the hub (silently overridden to Eddie by
 * the session bootstrapper) and Eddie in a regular project (a hub engine
 * without a hub).
 */
class RecipeControllerTest {

    private static final String TENANT = "acme";

    private RecipeLoader recipeLoader;
    private RecipeCategoriesService categories;
    private RequestAuthority authority;
    private ProjectService projectService;
    private RecipeController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        recipeLoader = mock(RecipeLoader.class);
        categories = mock(RecipeCategoriesService.class);
        authority = mock(RequestAuthority.class);
        projectService = mock(ProjectService.class);
        controller = new RecipeController(recipeLoader, categories, authority, projectService);

        // arrange() is presentation-only here — pass the filtered list through.
        when(categories.arrange(anyString(), any(), any()))
                .thenAnswer(inv -> RecipeListedResponse.builder()
                        .recipes((List<RecipeListedDto>) inv.getArgument(2))
                        .categories(List.of())
                        .build());
        when(recipeLoader.listAll(TENANT, "myproject"))
                .thenReturn(List.of(
                        recipe("arthur", RecipeProjectKind.NORMAL),
                        recipe("eddie", RecipeProjectKind.SYSTEM),
                        recipe("either", RecipeProjectKind.ANY)));
    }

    @Test
    void normalProject_showsNormalAndAnyRecipes() {
        givenProjectKind(ProjectKind.NORMAL);

        List<String> names = listedNames();

        assertThat(names).containsExactly("arthur", "either");
    }

    @Test
    void hubProject_showsSystemAndAnyRecipes() {
        givenProjectKind(ProjectKind.SYSTEM);

        List<String> names = listedNames();

        assertThat(names).containsExactly("eddie", "either");
    }

    @Test
    void unknownProject_defaultsToNormalFilter() {
        when(projectService.findByTenantAndName(TENANT, "myproject")).thenReturn(Optional.empty());

        List<String> names = listedNames();

        // Enforce passed but the project doc is gone mid-request — the
        // picker for a project that no longer exists has no wrong answer,
        // so the regular-project view is the safe default.
        assertThat(names).containsExactly("arthur", "either");
    }

    @SuppressWarnings("unchecked")
    private List<String> listedNames() {
        RecipeListedResponse response = controller.listed(TENANT, "myproject", mock(HttpServletRequest.class));
        return response.getRecipes().stream().map(RecipeListedDto::getName).toList();
    }

    private void givenProjectKind(ProjectKind kind) {
        ProjectDocument project = new ProjectDocument();
        project.setTenantId(TENANT);
        project.setName("myproject");
        project.setKind(kind);
        when(projectService.findByTenantAndName(TENANT, "myproject")).thenReturn(Optional.of(project));
    }

    /** Full canonical constructor — the compatibility one hardcodes NORMAL. */
    private static ResolvedRecipe recipe(String name, RecipeProjectKind projectKind) {
        return new ResolvedRecipe(
                name,
                "test recipe",
                "arthur", // engine
                Map.of(),
                null, // promptPrefix
                PromptMode.APPEND,
                null, // dataRelayCorrection
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(), // modes
                Map.of(), // profiles
                List.of(), // defaultActiveSkills
                null, // allowedSkills
                List.of(), // triggerKeywords
                false, // locked
                false, // internal
                true, // listed
                false, // web
                projectKind,
                null, // title
                null, // category
                null, // webTheme
                List.of(), // tags
                List.of(), // guards
                List.of(), // tenants
                RecipeSource.RESOURCE);
    }
}
