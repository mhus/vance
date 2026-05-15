import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';

/**
 * Project-default RAG (`_documents`) status + reindex composable.
 * Wraps {@code /brain/{tenant}/projects/{project}/rag/{status,reindex}}.
 */

export interface RagStatusDto {
  exists: boolean;
  ragId: string | null;
  embeddingProvider: string | null;
  embeddingModel: string | null;
  chunkCount: number;
  createdAt: string | null;
}

export interface ReindexResponseDto {
  rebuild: boolean;
  documentsQueued: number;
}

export interface UseRag {
  status: Ref<RagStatusDto | null>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  lastResult: Ref<ReindexResponseDto | null>;
  load: (projectId: string) => Promise<void>;
  reindex: (projectId: string, rebuild: boolean) => Promise<void>;
  clear: () => void;
}

export function useRag(): UseRag {
  const status = ref<RagStatusDto | null>(null);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const lastResult = ref<ReindexResponseDto | null>(null);

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      status.value = await brainFetch<RagStatusDto>(
        'GET',
        `projects/${encodeURIComponent(projectId)}/rag/status`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load RAG status.';
      status.value = null;
    } finally {
      loading.value = false;
    }
  }

  async function reindex(projectId: string, rebuild: boolean): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      lastResult.value = await brainFetch<ReindexResponseDto>(
        'POST',
        `projects/${encodeURIComponent(projectId)}/rag/reindex?rebuild=${rebuild ? 'true' : 'false'}`,
      );
      // Refresh the status so the user sees the (possibly recreated) RAG row.
      await load(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Reindex failed.';
    } finally {
      busy.value = false;
    }
  }

  function clear(): void {
    status.value = null;
    error.value = null;
    lastResult.value = null;
  }

  return { status, loading, busy, error, lastResult, load, reindex, clear };
}
