import { ref, type Ref } from 'vue';
import type { UsageReportDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Reactive wrapper around the tenant-wide LLM usage / cost reports.
 *
 * <p>Hits {@code GET /brain/{tenant}/usage/summary|by-project|by-model}
 * with optional from/to/groupBy params. Three independent refs so the
 * UI can render time-series + top-project + top-model in parallel
 * without coupling fetches.
 *
 * <p>Each loader takes the params it needs and writes the response
 * into its own ref. Loading + error are merged across all three so a
 * single spinner / banner can drive the tab.
 */
export function useUsageReport(): {
  summary: Ref<UsageReportDto | null>;
  byProject: Ref<UsageReportDto | null>;
  byModel: Ref<UsageReportDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  loadAll: (params: UsageQuery) => Promise<void>;
  reset: () => void;
} {
  const summary = ref<UsageReportDto | null>(null);
  const byProject = ref<UsageReportDto | null>(null);
  const byModel = ref<UsageReportDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function loadAll(params: UsageQuery): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const [s, p, m] = await Promise.all([
        brainFetch<UsageReportDto>('GET', `usage/summary?${buildQuery({
          from: params.from,
          to: params.to,
          groupBy: params.groupBy,
          projectId: params.projectId,
        })}`),
        brainFetch<UsageReportDto>('GET', `usage/by-project?${buildQuery({
          from: params.from,
          to: params.to,
        })}`),
        brainFetch<UsageReportDto>('GET', `usage/by-model?${buildQuery({
          from: params.from,
          to: params.to,
        })}`),
      ]);
      summary.value = s;
      byProject.value = p;
      byModel.value = m;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load usage report.';
      summary.value = null;
      byProject.value = null;
      byModel.value = null;
    } finally {
      loading.value = false;
    }
  }

  function reset(): void {
    summary.value = null;
    byProject.value = null;
    byModel.value = null;
    error.value = null;
  }

  return { summary, byProject, byModel, loading, error, loadAll, reset };
}

export interface UsageQuery {
  from?: string;
  to?: string;
  groupBy?: 'day' | 'week' | 'month';
  projectId?: string | null;
}

function buildQuery(params: Record<string, string | null | undefined>): string {
  const out: string[] = [];
  for (const [k, v] of Object.entries(params)) {
    if (v == null || v === '') continue;
    out.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  }
  return out.join('&');
}
