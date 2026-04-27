package de.mhus.vance.shared.recipe;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link RecipeDocument}. Package-private —
 * callers go through {@link RecipeService}.
 */
interface RecipeRepository extends MongoRepository<RecipeDocument, String> {

    Optional<RecipeDocument> findByTenantIdAndScopeAndName(
            String tenantId, RecipeScope scope, String name);

    Optional<RecipeDocument> findByTenantIdAndProjectIdAndName(
            String tenantId, String projectId, String name);

    List<RecipeDocument> findByTenantIdAndScope(String tenantId, RecipeScope scope);

    List<RecipeDocument> findByTenantIdAndProjectId(String tenantId, String projectId);
}
