import { ref, type Ref } from 'vue';
import type { UsageReportDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Reactive wrapper around the tenant-wide LLM usage / cost reports.
 *
 * <p>Hits {@code GET /brain/{tenant}/usage/summary|by-project|by-model|
 * by-caller|by-recipe} with optional from/to/groupBy params. One ref
 * per cut so the UI can render them in parallel without coupling
 * fetches.
 *
 * <p>Each loader takes the params it needs and writes the response
 * into its own ref. Loading + error are merged across all of them so a
 * single spinner / banner can drive the tab. A cut that fails leaves
 * its ref at {@code null} but does not wipe the cuts that answered —
 * the banner reports, the data stays.
 */
export function useUsageReport(): {
  summary: Ref<UsageReportDto | null>;
  byProject: Ref<UsageReportDto | null>;
  byModel: Ref<UsageReportDto | null>;
  byCaller: Ref<UsageReportDto | null>;
  byRecipe: Ref<UsageReportDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  loadAll: (params: UsageQuery) => Promise<void>;
  reset: () => void;
} {
  const summary = ref<UsageReportDto | null>(null);
  const byProject = ref<UsageReportDto | null>(null);
  const byModel = ref<UsageReportDto | null>(null);
  const byCaller = ref<UsageReportDto | null>(null);
  const byRecipe = ref<UsageReportDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function loadAll(params: UsageQuery): Promise<void> {
    loading.value = true;
    error.value = null;
    const window = buildQuery({ from: params.from, to: params.to });
    try {
      // allSettled, not all: the five cuts are independent reads, and one
      // failing endpoint must not blank the four that answered — a single
      // 500 (the Mongo 4.4 summary crash) made the whole view look like
      // "nothing was recorded at all" while the database was fine.
      // Failed cuts stay null, the error banner names the failure, the
      // rest renders.
      const [s, p, m, c, r] = await Promise.allSettled([
        brainFetch<UsageReportDto>('GET', `usage/summary?${buildQuery({
          from: params.from,
          to: params.to,
          groupBy: params.groupBy,
          projectId: params.projectId,
        })}`),
        brainFetch<UsageReportDto>('GET', `usage/by-project?${window}`),
        brainFetch<UsageReportDto>('GET', `usage/by-model?${window}`),
        brainFetch<UsageReportDto>('GET', `usage/by-caller?${window}`),
        brainFetch<UsageReportDto>('GET', `usage/by-recipe?${window}`),
      ]);
      if (s.status === 'fulfilled') summary.value = s.value;
      if (p.status === 'fulfilled') byProject.value = p.value;
      if (m.status === 'fulfilled') byModel.value = m.value;
      if (c.status === 'fulfilled') byCaller.value = c.value;
      if (r.status === 'fulfilled') byRecipe.value = r.value;
      for (const result of [s, p, m, c, r]) {
        if (result.status === 'rejected') {
          error.value = result.reason instanceof Error
            ? result.reason.message
            : 'Failed to load usage report.';
          break;
        }
      }
    } finally {
      loading.value = false;
    }
  }

  function reset(): void {
    summary.value = null;
    byProject.value = null;
    byModel.value = null;
    byCaller.value = null;
    byRecipe.value = null;
    error.value = null;
  }

  return { summary, byProject, byModel, byCaller, byRecipe, loading, error, loadAll, reset };
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
