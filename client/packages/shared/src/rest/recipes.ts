import type { RecipeListedDto } from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * GET /brain/{tenant}/projects/{project}/recipes/listed — recipes that
 * opt into the user-facing picker via {@code listed: true} in their YAML.
 * Filters out helper recipes ({@code internal: true}); the server merges
 * the project / _vance / bundled cascade and sorts alphabetically by
 * display title.
 *
 * <p>Used by the chat session-bootstrap picker to populate the recipe
 * dropdown. The "Default" entry is rendered by the client and sends
 * {@code chatRecipe: null} on bootstrap — recipes listed here are sent
 * as their own {@code name}.
 */
export async function listProjectRecipes(
  projectId: string,
): Promise<RecipeListedDto[]> {
  return brainFetch<RecipeListedDto[]>(
    'GET',
    `projects/${encodeURIComponent(projectId)}/recipes/listed`,
  );
}
