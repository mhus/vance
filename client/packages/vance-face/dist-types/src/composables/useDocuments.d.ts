import { type Ref } from 'vue';
import type { DocumentCreateRequest, DocumentDto, DocumentSummary, DocumentUpdateRequest } from '@vance/generated';
export interface UploadOptions {
    file: File;
    /** Defaults to the file's `name` when omitted. */
    path?: string;
    title?: string;
    /** Already trimmed array — `useDocuments.upload` joins them. */
    tags?: string[];
    /** Defaults to the file's own MIME type. */
    mimeType?: string;
}
/**
 * Reactive wrapper around the document REST endpoints. One instance per
 * editor — keeps current page state and exposes `loadPage` / `loadOne` /
 * `update` for the editor to call.
 */
export declare function useDocuments(pageSize?: number): {
    items: Ref<DocumentSummary[]>;
    page: Ref<number>;
    totalCount: Ref<number>;
    pageSize: Ref<number>;
    selected: Ref<DocumentDto | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    folders: Ref<string[]>;
    pathPrefix: Ref<string>;
    loadPage: (projectId: string, page: number, pathPrefix?: string) => Promise<void>;
    loadFolders: (projectId: string) => Promise<void>;
    loadOne: (id: string) => Promise<void>;
    clearSelection: () => void;
    create: (projectId: string, body: DocumentCreateRequest) => Promise<DocumentDto | null>;
    upload: (projectId: string, opts: UploadOptions) => Promise<DocumentDto | null>;
    update: (id: string, body: DocumentUpdateRequest) => Promise<void>;
    remove: (id: string) => Promise<boolean>;
};
//# sourceMappingURL=useDocuments.d.ts.map