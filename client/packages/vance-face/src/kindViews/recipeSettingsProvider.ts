import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';

/**
 * Settings contribution of the recipe kind: the named recipe bundles under
 * {@code _vance/recipes/} (one YAML per recipe, resolved by filename — see
 * {@code RecipeLoader.RECIPE_PATH_PREFIX}). The Settings page's "Bereiche"
 * tab shows this as the Recipes area: the first management surface recipes
 * have ever had.
 *
 * <p><b>Raw text, deliberately.</b> Recipe YAMLs are the most complex
 * configuration documents the system has (engine, params, prompt templates,
 * tool adjustments, profiles, guards) — a form view would either render a
 * fraction of the fields or duplicate the loader's parse. The kind entry
 * therefore registers no view and no codec, and the settings host falls
 * back to its raw YAML editor with save: inventory, create, delete, edit in
 * place. A form view can ride along later without touching this provider.
 *
 * <p><b>Flat listing, deliberately.</b> The loader resolves recipes by name
 * directly under the prefix; the Slart architect's working trees
 * ({@code _user/}, {@code _slart/}) are sub-directories of its own, not
 * operator recipes. A single-level folder listing shows exactly what the
 * loader reads and keeps those working trees out of the inventory.
 *
 * <p><b>Location-based inventory</b>, like the model area: the loader scans
 * the path and never looks at kind markers, so no kind filter. The server's
 * {@code RecipeDocKindHandler} types writes into the folder and validates
 * what would make a recipe silently unspawnable.
 *
 * <p>Served for the tenant and project layers only — the recipe cascade
 * (project → {@code _vance} → classpath) has no user layer.
 */
export const recipeSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.recipes.title',
  createHintKey: 'settings.areas.recipes.createHint',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listRecipes(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Recipes are not available in the user scope.');
    }
    const trimmed = name.trim();
    if (!trimmed) throw new Error('A name is required.');
    const path = `_vance/recipes/${trimmed}.yaml`;
    const body: DocumentCreateRequest = {
      path,
      // Minimal body the loader accepts: description and engine are its two
      // required fields; everything else grows in the raw editor.
      inlineText:
        '# New recipe — the loader needs description and engine; everything else is optional.\n'
        + '$meta:\n  kind: vance-recipe\n'
        + `title: ${trimmed}\n`
        + 'description: |\n  What this recipe does.\n'
        + 'engine: arthur\n'
        + 'listed: true\n',
    };
    const params = new URLSearchParams({ projectId: scope.projectId });
    const doc = await brainFetch<DocumentDto>(
      'POST',
      `documents?${params.toString()}`,
      { body },
    );
    return {
      name: trimmed,
      title: trimmed,
      kindId: 'vance-recipe',
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listRecipes(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/recipes/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => (doc.path ?? '').endsWith('.yaml'))
    .map((doc: DocumentSummary) => {
      const stem = doc.path!.substring('_vance/recipes/'.length).replace(/\.yaml$/, '');
      return {
        name: stem,
        title: doc.title || stem,
        kindId: 'vance-recipe',
        documentId: doc.id,
        projectId: doc.projectId,
        path: doc.path,
      };
    });
}
