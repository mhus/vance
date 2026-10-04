import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { TrillianInsightsDto } from '@vance/generated';

/**
 * REST access to the Trillian state view (`GET admin/trillian`) plus the
 * two loop controls (`POST admin/trillian/{id}/pause|resume`).
 *
 * Pause/resume answer with the refreshed state of the touched pair, so the
 * row is replaced in place instead of refetching the whole list — the same
 * contract the `//trillian stop|continue` commands have on the wire.
 */
export interface UseTrillianInsights {
  items: Ref<TrillianInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: () => Promise<void>;
  pause: (controlProcessId: string) => Promise<void>;
  resume: (controlProcessId: string) => Promise<void>;
}

export function useTrillianInsights(): UseTrillianInsights {
  const items = ref<TrillianInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const rows = await brainFetch<TrillianInsightsDto[]>('GET', 'admin/trillian');
      items.value = Array.isArray(rows) ? rows : [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load Trillian state.';
      items.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function pause(controlProcessId: string): Promise<void> {
    await toggle(controlProcessId, 'pause');
  }

  async function resume(controlProcessId: string): Promise<void> {
    await toggle(controlProcessId, 'resume');
  }

  async function toggle(controlProcessId: string, verb: 'pause' | 'resume'): Promise<void> {
    const updated = await brainFetch<TrillianInsightsDto>(
      'POST',
      `admin/trillian/${encodeURIComponent(controlProcessId)}/${verb}`,
    );
    items.value = items.value.map((it) =>
      it.control.processId === updated.control.processId ? updated : it,
    );
  }

  return { items, loading, error, load, pause, resume };
}