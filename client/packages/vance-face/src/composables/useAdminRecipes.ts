import { ref, type Ref } from 'vue';
import type {
  RecipeDto,
  RecipeWriteRequest,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Read + write recipes at tenant scope (when {@code projectId} is null
 * or empty) or project scope (when set). The "effective" list walks
 * Project → Tenant → Bundled and returns one entry per name with a
 * {@code source} field describing where the visible copy lives.
 */
export function useAdminRecipes(): {
  recipes: Ref<RecipeDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  loadEffective: (projectId: string | null) => Promise<void>;
  upsert: (
    projectId: string | null,
    name: string,
    body: RecipeWriteRequest,
  ) => Promise<RecipeDto>;
  remove: (projectId: string | null, name: string) => Promise<void>;
} {
  const recipes = ref<RecipeDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function loadEffective(projectId: string | null): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams();
      if (projectId) params.set('projectId', projectId);
      const path = `admin/recipes/effective${params.toString() ? '?' + params.toString() : ''}`;
      recipes.value = await brainFetch<RecipeDto[]>('GET', path);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load recipes.';
      recipes.value = [];
    } finally {
      loading.value = false;
    }
  }

  function pathFor(projectId: string | null, name: string): string {
    return projectId
      ? `admin/projects/${encodeURIComponent(projectId)}/recipes/${encodeURIComponent(name)}`
      : `admin/recipes/${encodeURIComponent(name)}`;
  }

  async function upsert(
    projectId: string | null,
    name: string,
    body: RecipeWriteRequest,
  ): Promise<RecipeDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<RecipeDto>('PUT', pathFor(projectId, name), { body });
      await loadEffective(projectId);
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save recipe.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(projectId: string | null, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>('DELETE', pathFor(projectId, name));
      await loadEffective(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete recipe.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { recipes, loading, busy, error, loadEffective, upsert, remove };
}
