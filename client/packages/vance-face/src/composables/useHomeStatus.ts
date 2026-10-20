import { ref, watch, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { HomeStatusDto } from '@vance/generated';

/**
 * REST loader for the landing-page status hints next to the "Chat" and
 * "Dokumente" tiles. One roundtrip, one endpoint
 * ({@code GET /brain/{tenant}/home/status}, no auth — every reader of
 * the home view can see the same hints).
 *
 * <p>Each field is independently nullable. The SPA renders nothing for
 * a null — a transient outage on one component never reads as "off" or
 * "full" when the actual answer was "unknown".
 *
 * <p>Refetched only on demand: the landing view is short-lived, every
 * visit is a fresh mount. No timer, no subscription, no debouncing.
 */
export interface UseHomeStatus {
  status: Ref<HomeStatusDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (projectId?: string | null) => Promise<boolean>;
}

export function useHomeStatus(): UseHomeStatus {
  const status = ref<HomeStatusDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(projectId?: string | null): Promise<boolean> {
    loading.value = true;
    error.value = null;
    try {
      const qs = projectId && projectId.trim().length > 0
        ? `?projectId=${encodeURIComponent(projectId)}`
        : '';
      status.value = await brainFetch<HomeStatusDto>('GET', `home/status${qs}`);
      return true;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load home status.';
      return false;
    } finally {
      loading.value = false;
    }
  }

  return { status, loading, error, load };
}

/**
 * Convenience wrapper: returns a loader that re-fetches whenever
 * {@code projectIdRef} changes. Used by the session picker so the budget
 * banner tracks the selected project without the parent having to
 * wire a watch.
 */
export function useProjectHomeStatus(projectIdRef: Ref<string | null | undefined>): UseHomeStatus {
  const home = useHomeStatus();
  watch(
    projectIdRef,
    (id) => {
      void home.load(id ?? null);
    },
    { immediate: true },
  );
  return home;
}
