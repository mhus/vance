import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { LeaseCounterInsightsDto, LeaseHolderInsightsDto, LeaseInsightsDto } from '@vance/generated';

/**
 * REST loader for the tenant's resource leases (Insights → Leases):
 * held leases plus the tenant/project/user funnel counters. `revoke`
 * frees one lease — the brain runs the lease type's cleanup handler —
 * and reloads the view.
 */
export interface UseLeaseInsights {
  holders: Ref<LeaseHolderInsightsDto[]>;
  counters: Ref<LeaseCounterInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: () => Promise<void>;
  revoke: (token: string) => Promise<void>;
}

export function useLeaseInsights(): UseLeaseInsights {
  const holders = ref<LeaseHolderInsightsDto[]>([]);
  const counters = ref<LeaseCounterInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const dto = await brainFetch<LeaseInsightsDto>('GET', 'admin/leases');
      holders.value = dto.holders ?? [];
      counters.value = dto.counters ?? [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load leases.';
      holders.value = [];
      counters.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function revoke(token: string): Promise<void> {
    let failure: string | null = null;
    try {
      await brainFetch<void>('POST', `admin/leases/${encodeURIComponent(token)}/revoke`);
    } catch (e) {
      // A 404 means the lease is already gone — the reload shows that too.
      failure = e instanceof Error ? e.message : 'Failed to revoke the lease.';
    }
    await load();
    // After the reload, which resets the error for its own outcome.
    if (failure !== null) error.value = failure;
  }

  return { holders, counters, loading, error, load, revoke };
}
