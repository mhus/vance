import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  EffectiveRecipeDto,
  EffectiveToolDto,
  ToolHealthEntryDto,
  ZarniwoopInsightsDto,
} from '@vance/generated';

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

export interface UseZarniwoopInsights {
  instances: Ref<ZarniwoopInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (projectId: string) => Promise<void>;
  clear: () => void;
  setOverride: (projectId: string, instanceId: string, enabled: boolean) => Promise<void>;
  clearOverride: (projectId: string, instanceId: string) => Promise<void>;
}

export function useZarniwoopInsights(): UseZarniwoopInsights {
  const instances = ref<ZarniwoopInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      instances.value = await brainFetch<ZarniwoopInsightsDto[]>(
        'GET',
        `admin/projects/${encodeURIComponent(projectId)}/insights/zarniwoop`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load search providers.';
      instances.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    instances.value = [];
    error.value = null;
  }

  async function setOverride(
    projectId: string,
    instanceId: string,
    enabled: boolean,
  ): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      instances.value = await brainFetch<ZarniwoopInsightsDto[]>(
        'PUT',
        `admin/projects/${encodeURIComponent(projectId)}/insights/zarniwoop/${encodeURIComponent(instanceId)}/override`,
        { body: { enabled } },
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to set override.';
    } finally {
      loading.value = false;
    }
  }

  async function clearOverride(projectId: string, instanceId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      instances.value = await brainFetch<ZarniwoopInsightsDto[]>(
        'DELETE',
        `admin/projects/${encodeURIComponent(projectId)}/insights/zarniwoop/${encodeURIComponent(instanceId)}/override`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to clear override.';
    } finally {
      loading.value = false;
    }
  }

  return { instances, loading, error, load, clear, setOverride, clearOverride };
}

/**
 * Tool-Health + active cooldowns for a project. Pairs with the
 * ProjectToolsTab so each tool row can show a status badge and an
 * expandable cooldown list. {@code clearCooldown} maps to the
 * admin-only clear-cooldown endpoint and triggers a reload.
 */
export interface UseToolHealth {
  entries: Ref<ToolHealthEntryDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (projectId: string) => Promise<void>;
  clear: () => void;
  clearCooldown: (
    projectId: string,
    toolName: string,
    errorSignature: string,
    userId: string | null,
  ) => Promise<void>;
}

export function useToolHealth(): UseToolHealth {
  const entries = ref<ToolHealthEntryDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({ scope: 'PROJECT', scopeId: projectId });
      entries.value = await brainFetch<ToolHealthEntryDto[]>(
        'GET',
        `admin/tool-health?${params.toString()}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load tool health.';
      entries.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    entries.value = [];
    error.value = null;
  }

  async function clearCooldown(
    projectId: string,
    toolName: string,
    errorSignature: string,
    userId: string | null,
  ): Promise<void> {
    try {
      await brainFetch<{ cleared: boolean }>(
        'POST',
        'admin/tool-health/clear-cooldown',
        {
          body: {
            scope: 'PROJECT',
            scopeId: projectId,
            toolName,
            errorSignature,
            userId,
          },
        },
      );
      await load(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to clear cooldown.';
    }
  }

  return { entries, loading, error, load, clear, clearCooldown };
}
