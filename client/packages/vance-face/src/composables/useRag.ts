import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';

/**
 * Project-default RAG (`_documents`) status + reindex composable.
 * Wraps {@code /brain/{tenant}/projects/{project}/rag/{status,reindex}}.
 */

export interface RagStatusDto {
  exists: boolean;
  ragId: string | null;
  /** Provider baked into the existing RAG at create time. May differ from `effectiveProvider`. */
  embeddingProvider: string | null;
  embeddingModel: string | null;
  chunkCount: number;
  createdAt: string | null;
  /**
   * Cascade-resolved (tenant→project) `ai.embedding.provider` setting.
   * Always present; `"none"` means RAG is disabled for this scope.
   */
  effectiveProvider: string;
  /** `effectiveProvider !== "none"`. Drives whether the UI shows actions / search. */
  enabled: boolean;
}

export interface ReindexResponseDto {
  rebuild: boolean;
  documentsQueued: number;
}

export interface SearchHitDto {
  sourceRef: string | null;
  position: number;
  content: string;
  score: number;
}

export interface SearchResponseDto {
  hits: SearchHitDto[];
}

export interface UseRag {
  status: Ref<RagStatusDto | null>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  lastResult: Ref<ReindexResponseDto | null>;
  searchHits: Ref<SearchHitDto[]>;
  searchQuery: Ref<string>;
  searching: Ref<boolean>;
  searchError: Ref<string | null>;
  searched: Ref<boolean>;
  load: (projectId: string) => Promise<void>;
  reindex: (projectId: string, rebuild: boolean) => Promise<void>;
  search: (projectId: string, query: string) => Promise<void>;
  clear: () => void;
}

export function useRag(): UseRag {
  const status = ref<RagStatusDto | null>(null);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const lastResult = ref<ReindexResponseDto | null>(null);
  const searchHits = ref<SearchHitDto[]>([]);
  const searchQuery = ref('');
  const searching = ref(false);
  const searchError = ref<string | null>(null);
  const searched = ref(false);

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

  async function search(projectId: string, query: string): Promise<void> {
    searching.value = true;
    searchError.value = null;
    try {
      const response = await brainFetch<SearchResponseDto>(
        'POST',
        `projects/${encodeURIComponent(projectId)}/rag/search`,
        { body: { query } },
      );
      searchHits.value = response.hits ?? [];
      searched.value = true;
    } catch (e) {
      searchError.value = e instanceof Error ? e.message : 'Search failed.';
      searchHits.value = [];
      searched.value = true;
    } finally {
      searching.value = false;
    }
  }

  function clear(): void {
    status.value = null;
    error.value = null;
    lastResult.value = null;
    searchHits.value = [];
    searchQuery.value = '';
    searchError.value = null;
    searched.value = false;
  }

  return {
    status,
    loading,
    busy,
    error,
    lastResult,
    searchHits,
    searchQuery,
    searching,
    searchError,
    searched,
    load,
    reindex,
    search,
    clear,
  };
}
