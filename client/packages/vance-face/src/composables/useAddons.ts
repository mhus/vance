import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { AddonInsightDto } from '@vance/generated';

/**
 * REST loader for the Addons insights endpoint. Returns one row per
 * addon in the system (enabled and disabled alike) combined with the
 * unpack state under {@code /shared/addons/}. Read-only; the Addons
 * tab is a debug view, not an admin control surface.
 */
export interface UseAddonInsights {
  addons: Ref<AddonInsightDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: () => Promise<void>;
  clear: () => void;
}

export function useAddonInsights(): UseAddonInsights {
  const addons = ref<AddonInsightDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      addons.value = await brainFetch<AddonInsightDto[]>('GET', 'admin/addons');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load addons.';
      addons.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    addons.value = [];
    error.value = null;
  }

  return { addons, loading, error, load, clear };
}
