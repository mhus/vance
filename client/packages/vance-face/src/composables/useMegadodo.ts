import { computed, ref, type ComputedRef, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import {
  MegadodoPhase,
  type MegadodoEventDto,
  type MegadodoPageDto,
} from '@vance/generated';

/**
 * Filters the feed offers. Deliberately few — the point of Megadodo is
 * that a day of activity fits on one screen, not that it can be sliced
 * every which way.
 */
export interface MegadodoFilters {
  /** Only failures. The one-click switch, not a hand-assembled query. */
  onlyErrors: boolean;
  /** Action prefix, e.g. `scheduler.` — '' means everything. */
  action: string;
  /** Substring of the message. */
  text: string;
}

/**
 * One operation, folded from its rows. A scheduler run emits START and
 * END; the reader wants one line with a duration and an outcome, and the
 * individual rows only on demand.
 */
export interface MegadodoOperation {
  traceId: string;
  /** The row that names the operation — START if there is one. */
  head: MegadodoEventDto;
  /** The row that closed it, if it is closed. */
  tail: MegadodoEventDto | null;
  rows: MegadodoEventDto[];
  /** Milliseconds between head and tail; null while still open. */
  durationMs: number | null;
  /** No END row yet — either running or stuck. */
  open: boolean;
  failed: boolean;
}

export interface UseMegadodo {
  operations: ComputedRef<MegadodoOperation[]>;
  loading: Ref<boolean>;
  loadingMore: Ref<boolean>;
  error: Ref<string | null>;
  hasMore: ComputedRef<boolean>;
  filters: Ref<MegadodoFilters>;
  load(projectId: string | null): Promise<void>;
  loadMore(projectId: string | null): Promise<void>;
  clear(): void;
}

const PAGE_SIZE = 100;

export function useMegadodo(): UseMegadodo {
  const rows = ref<MegadodoEventDto[]>([]);
  const cursor = ref<string | null>(null);
  const loading = ref(false);
  const loadingMore = ref(false);
  const error = ref<string | null>(null);
  const filters = ref<MegadodoFilters>({ onlyErrors: false, action: '', text: '' });

  function query(projectId: string | null, next: string | null): string {
    const params = new URLSearchParams();
    if (projectId) params.set('projectId', projectId);
    if (filters.value.onlyErrors) params.set('minSeverity', 'ERROR');
    if (filters.value.action) params.set('action', filters.value.action);
    if (filters.value.text.trim()) params.set('q', filters.value.text.trim());
    if (next) params.set('cursor', next);
    params.set('limit', String(PAGE_SIZE));
    return `megadodo?${params.toString()}`;
  }

  async function fetchPage(projectId: string | null, next: string | null): Promise<void> {
    const page = await brainFetch<MegadodoPageDto>('GET', query(projectId, next));
    const items = page.items ?? [];
    rows.value = next ? [...rows.value, ...items] : items;
    cursor.value = page.nextCursor ?? null;
  }

  async function load(projectId: string | null): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      await fetchPage(projectId, null);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load the activity feed.';
      rows.value = [];
      cursor.value = null;
    } finally {
      loading.value = false;
    }
  }

  async function loadMore(projectId: string | null): Promise<void> {
    if (!cursor.value || loadingMore.value) return;
    loadingMore.value = true;
    try {
      await fetchPage(projectId, cursor.value);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load more.';
    } finally {
      loadingMore.value = false;
    }
  }

  function clear(): void {
    rows.value = [];
    cursor.value = null;
    error.value = null;
  }

  /**
   * Fold rows into operations by traceId, newest first.
   *
   * Rows arrive newest-first, so the first row of a group is the END and
   * the last is the START. A group whose START fell off the end of the
   * loaded window still renders — its newest row stands in as the head,
   * which is better than hiding activity because its beginning is older
   * than the page.
   */
  const operations = computed<MegadodoOperation[]>(() => {
    const byTrace = new Map<string, MegadodoEventDto[]>();
    for (const row of rows.value) {
      const list = byTrace.get(row.traceId);
      if (list) list.push(row);
      else byTrace.set(row.traceId, [row]);
    }
    const out: MegadodoOperation[] = [];
    for (const [traceId, group] of byTrace) {
      // group is newest-first; oldest last.
      const oldest = group[group.length - 1]!;
      const newest = group[0]!;
      const start = group.find((r) => r.phase === MegadodoPhase.START) ?? oldest;
      const end = group.find((r) => r.phase === MegadodoPhase.END) ?? null;
      const single = group.find((r) => r.phase === MegadodoPhase.SINGLE) ?? null;
      const head = start ?? single ?? newest;
      const tail = end ?? single;
      const durationMs =
        end && start && end !== start
          ? new Date(end.timestamp).getTime() - new Date(start.timestamp).getTime()
          : null;
      out.push({
        traceId,
        head,
        tail,
        rows: group,
        durationMs,
        open: !end && !single,
        failed: group.some((r) => r.outcome === 'failure'),
      });
    }
    // Sort by the newest row of each operation — an operation that just
    // finished belongs at the top even if it started yesterday.
    return out.sort(
      (a, b) =>
        new Date(b.rows[0]!.timestamp).getTime() - new Date(a.rows[0]!.timestamp).getTime(),
    );
  });

  const hasMore = computed(() => cursor.value !== null);

  return { operations, loading, loadingMore, error, hasMore, filters, load, loadMore, clear };
}
