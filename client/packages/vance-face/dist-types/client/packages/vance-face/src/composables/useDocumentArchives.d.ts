import { type Ref } from 'vue';
import type { DocumentArchiveDto, DocumentArchiveSummary, DocumentDto } from '@vance/generated';
/**
 * Reactive wrapper around the document-archive REST endpoints. One instance
 * per editor — caches the version list for the currently-selected document
 * plus the currently-previewed archive entry. The owning editor calls
 * {@link load} when a document opens, {@link preview} when the user picks a
 * version, {@link restore} / {@link remove} for the two destructive actions.
 *
 * <p>Same posture as {@code useDocuments}: this composable owns its loading /
 * error state so the editor can render a banner without juggling Promise
 * lifecycles.
 */
export declare function useDocumentArchives(): {
    items: Ref<DocumentArchiveSummary[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    preview: Ref<DocumentArchiveDto | null>;
    previewLoading: Ref<boolean>;
    load: (documentId: string) => Promise<void>;
    open: (documentId: string, archiveId: string) => Promise<void>;
    clearPreview: () => void;
    remove: (documentId: string, archiveId: string) => Promise<boolean>;
    restore: (documentId: string, archiveId: string) => Promise<DocumentDto | null>;
};
//# sourceMappingURL=useDocumentArchives.d.ts.map