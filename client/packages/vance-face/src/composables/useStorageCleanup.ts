import { ref, type Ref } from 'vue';
import type { StorageBreakdownDto, StorageCleanupResultDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Reactive wrapper around the admin storage-cleanup REST surface
 * ({@code GET /storage/breakdown}, {@code POST /cleanup/trash},
 * {@code POST /cleanup/archives}).
 *
 * <p>One composable, three loaders: the breakdown drives the Quotas tab's
 * "Live / Trash / Archives" rows, and the two actions return
 * {@link StorageCleanupResultDto} with the freed-byte count plus the
 * freshly-aggregated {@code usedBytes}/{@code limitBytes} — the server
 * invalidates the quota cache before responding, so the UI can render
 * the new meter without waiting for the 30 s {@code usageCache} TTL.
 *
 * <p>Errors surface verbatim — the dialog owns the
 * "are you sure?" confirm + the "X freed, Y left" toast, so a 403/404
 * on cleanup is an exceptional case worth a literal banner rather than
 * a swallowed null.
 */
export interface UseStorageCleanup {
  breakdown: Ref<StorageBreakdownDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  /** Reload the breakdown for one scope. {@code projectId} blank = tenant-wide. */
  loadBreakdown: (projectId?: string | null) => Promise<boolean>;
  /** Hard-delete every document under {@code _vance/trash/}. */
  emptyTrash: (projectId?: string | null) => Promise<StorageCleanupResultDto>;
  /** Keep the {@code keepLatest} newest archive entries per lineage, delete the rest. */
  thinArchives: (projectId: string | null | undefined, keepLatest: number) => Promise<StorageCleanupResultDto>;
  reset: () => void;
}

export function useStorageCleanup(): UseStorageCleanup {
  const breakdown = ref<StorageBreakdownDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function loadBreakdown(projectId?: string | null): Promise<boolean> {
    loading.value = true;
    error.value = null;
    try {
      const qs = projectId && projectId.trim().length > 0
        ? `?projectId=${encodeURIComponent(projectId)}`
        : '';
      breakdown.value = await brainFetch<StorageBreakdownDto>('GET', `storage/breakdown${qs}`);
      return true;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load storage breakdown.';
      return false;
    } finally {
      loading.value = false;
    }
  }

  async function emptyTrash(projectId?: string | null): Promise<StorageCleanupResultDto> {
    error.value = null;
    try {
      const qs = projectId && projectId.trim().length > 0
        ? `?projectId=${encodeURIComponent(projectId)}`
        : '';
      const result = await brainFetch<StorageCleanupResultDto>('POST', `cleanup/trash${qs}`);
      // Refresh the breakdown after the action so the meter reflects the freed bytes.
      await loadBreakdown(projectId);
      return result;
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Trash cleanup failed.';
      error.value = msg;
      throw new Error(msg, { cause: e });
    }
  }

  async function thinArchives(
      projectId: string | null | undefined,
      keepLatest: number,
  ): Promise<StorageCleanupResultDto> {
    error.value = null;
    try {
      const qs = projectId && projectId.trim().length > 0
        ? `?projectId=${encodeURIComponent(projectId)}`
        : '';
      const result = await brainFetch<StorageCleanupResultDto>(
          'POST',
          `cleanup/archives${qs}`,
          { body: { keepLatest } },
      );
      await loadBreakdown(projectId);
      return result;
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Archive cleanup failed.';
      error.value = msg;
      throw new Error(msg, { cause: e });
    }
  }

  function reset(): void {
    breakdown.value = null;
    error.value = null;
    loading.value = false;
  }

  return { breakdown, loading, error, loadBreakdown, emptyTrash, thinArchives, reset };
}
