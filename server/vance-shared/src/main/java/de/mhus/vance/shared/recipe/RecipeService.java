package de.mhus.vance.shared.recipe;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Recipe persistence + cascade lookup limited to the Mongo-stored
 * tiers (tenant, project). Bundled defaults from the YAML resource
 * are owned by the brain-side {@code BundledRecipeRegistry} and
 * combined by the brain-side {@code RecipeResolver} — this service
 * only knows about persistent overrides.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeService {

    private final RecipeRepository repository;

    /**
     * Find a recipe by name, walking project → tenant. Returns empty
     * if neither tier has a match — the caller is then expected to
     * fall back to bundled defaults (or fail).
     */
    public Optional<RecipeDocument> find(
            String tenantId, @Nullable String projectId, String name) {
        if (projectId != null) {
            Optional<RecipeDocument> project =
                    repository.findByTenantIdAndProjectIdAndName(tenantId, projectId, name);
            if (project.isPresent()) {
                return project;
            }
        }
        return repository.findByTenantIdAndScopeAndName(tenantId, RecipeScope.TENANT, name);
    }

    public List<RecipeDocument> listTenant(String tenantId) {
        return repository.findByTenantIdAndScope(tenantId, RecipeScope.TENANT);
    }

    public List<RecipeDocument> listProject(String tenantId, String projectId) {
        return repository.findByTenantIdAndProjectId(tenantId, projectId);
    }

    public RecipeDocument save(RecipeDocument doc) {
        RecipeDocument saved = repository.save(doc);
        log.info("Saved recipe tenant='{}' scope={} name='{}' id='{}'",
                saved.getTenantId(), saved.getScope(), saved.getName(), saved.getId());
        return saved;
    }

    public void delete(String id) {
        repository.deleteById(id);
    }
}
