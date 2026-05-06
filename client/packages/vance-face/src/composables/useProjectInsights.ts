import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { EffectiveRecipeDto, EffectiveToolDto } from '@vance/generated';

/**
 * REST loaders for the project-level insight tabs (Recipes / Tools).
 * Both endpoints walk the cascade ({@code project → _vance →
 * built-in/bundled}) and tag each surviving entry with its
 * {@code source} attribute so the UI can render origin badges.
 *
 * One project at a time — the caller invokes {@code load(projectId)}
 * after the user has picked a project in the insights sidebar.
 */
export interface UseEffectiveRecipes {
  recipes: Ref<EffectiveRecipeDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (projectId: string) => Promise<void>;
  clear: () => void;
}

export interface UseEffectiveTools {
  tools: Ref<EffectiveToolDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (projectId: string) => Promise<void>;
  clear: () => void;
}

export function useEffectiveRecipes(): UseEffectiveRecipes {
  const recipes = ref<EffectiveRecipeDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      recipes.value = await brainFetch<EffectiveRecipeDto[]>(
        'GET',
        `admin/projects/${encodeURIComponent(projectId)}/insights/recipes`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load recipes.';
      recipes.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    recipes.value = [];
    error.value = null;
  }

  return { recipes, loading, error, load, clear };
}

export function useEffectiveTools(): UseEffectiveTools {
  const tools = ref<EffectiveToolDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      tools.value = await brainFetch<EffectiveToolDto[]>(
        'GET',
        `admin/projects/${encodeURIComponent(projectId)}/insights/tools`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load tools.';
      tools.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    tools.value = [];
    error.value = null;
  }

  return { tools, loading, error, load, clear };
}
