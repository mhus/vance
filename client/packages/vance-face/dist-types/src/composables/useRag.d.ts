import { type Ref } from 'vue';
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
export declare function useRag(): UseRag;
//# sourceMappingURL=useRag.d.ts.map