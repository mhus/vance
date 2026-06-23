package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.recipe.RecipeListedDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>The endpoint enforces a {@link Resource.Project} READ
 * permission against the JWT — recipes are scoped to the project's
 * cascade view.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RecipeController {

    private final RecipeLoader recipeLoader;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/projects/{project}/recipes/listed")
    public List<RecipeListedDto> listed(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        List<ResolvedRecipe> recipes = recipeLoader.listAll(tenant, project);
        List<RecipeListedDto> out = new ArrayList<>();
        for (ResolvedRecipe r : recipes) {
            if (!r.listed() || r.internal()) {
                continue;
            }
            out.add(RecipeListedDto.builder()
                    .name(r.name())
                    .title(r.title())
                    .description(r.description())
                    .build());
        }
        out.sort(Comparator.comparing(
                dto -> dto.getTitle() != null ? dto.getTitle() : dto.getName(),
                String.CASE_INSENSITIVE_ORDER));
        return out;
    }
}
