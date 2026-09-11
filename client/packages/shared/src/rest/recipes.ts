import type { RecipeListedResponse } from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * GET /brain/{tenant}/projects/{project}/recipes/listed — recipes that
 * opt into the user-facing picker via `listed: true` in their YAML.
 * Filters out helper recipes (`internal: true`); the server merges the
 * project / _vance / bundled cascade.
 *
 * <p>Used by the chat session-bootstrap picker to populate the recipe
 * dropdown. The "Default" entry is rendered by the client and sends
 * `chatRecipe: null` on bootstrap — recipes listed here are sent as
 * their own `name`.
 *
 * <p>The response carries the recipe entries sorted for grouped
 * rendering (category-group order first, then display title
 * case-insensitive; entries without a category last) plus the category
 * metadata from `_vance/config/recipe_categories.yaml` in document
 * order. `categories` is empty when that document is absent or
 * malformed — group first-occurrence order and humanised category ids
 * are the fallback.
 */
export async function listProjectRecipes(
  projectId: string,
): Promise<RecipeListedResponse> {
  return brainFetch<RecipeListedResponse>(
    'GET',
    `projects/${encodeURIComponent(projectId)}/recipes/listed`,
  );
}
