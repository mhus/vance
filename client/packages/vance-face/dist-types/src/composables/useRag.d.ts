import { type Ref } from 'vue';
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
export declare function useRag(): UseRag;
//# sourceMappingURL=useRag.d.ts.map