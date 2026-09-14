package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.recipe.RecipeListedDto;
import de.mhus.vance.api.recipe.RecipeListedResponse;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the user-facing recipe picker.
 *
 * <p>{@code GET /brain/{tenant}/projects/{project}/recipes/listed}
 * returns every recipe that opts in via {@code listed: true} in its
 * YAML, resolved through the project → _vance → bundled cascade
 * (same merge semantics as {@link RecipeLoader#listAll}). Internal
 * helper recipes ({@code internal: true}) are excluded even when
 * they carry {@code listed: true}.
 *
 * <p>The list is filtered by the target project's kind: recipes marked
 * {@code projectKind: system} (Eddie) appear only in SYSTEM hub projects
 * ({@code _user_*}, {@code _tenant}), {@code normal} recipes (the
 * default) only in regular projects — see {@link RecipeProjectKind}.
 *
 * <p>The response carries the category metadata from
 * {@code _vance/config/recipe_categories.yaml} and the recipes sorted
 * for grouped rendering — see {@link RecipeCategoriesService#arrange}.
 *
 * <p>The endpoint enforces a {@link Resource.Project} READ
 * permission against the JWT — recipes are scoped to the project's
 * cascade view.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RecipeController {

    private final RecipeLoader recipeLoader;
    private final RecipeCategoriesService recipeCategoriesService;
    private final RequestAuthority authority;
    private final ProjectService projectService;

    @GetMapping("/brain/{tenant}/projects/{project}/recipes/listed")
    public RecipeListedResponse listed(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        // Hub projects (SYSTEM: _user_*, _tenant) and regular projects run
        // different worlds — the hub chat is always Eddie, normal projects
        // run Arthur and worker recipes. The picker must not offer a recipe
        // for the wrong kind: picking Arthur in the hub is silently ignored
        // by SessionChatBootstrapper, picking Eddie in a regular project
        // spawns a hub engine without a hub. Unknown project (enforce
        // passed, but the doc is gone mid-request) reads as NORMAL — the
        // picker for a project that no longer exists has no wrong answer.
        ProjectKind projectKind = projectService
                .findByTenantAndName(tenant, project)
                .map(ProjectDocument::getKind)
                .orElse(ProjectKind.NORMAL);

        List<ResolvedRecipe> recipes = recipeLoader.listAll(tenant, project);
        List<RecipeListedDto> out = new ArrayList<>();
        for (ResolvedRecipe r : recipes) {
            if (!r.listed() || r.internal()) {
                continue;
            }
            if (!r.projectKind().allowedIn(projectKind)) {
                continue;
            }
            out.add(RecipeListedDto.builder()
                    .name(r.name())
                    .title(r.title())
                    .description(r.description())
                    .category(r.category())
                    .build());
        }
        return recipeCategoriesService.arrange(tenant, project, out);
    }
}
