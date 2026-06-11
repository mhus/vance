import type { CortexDocument, FolderNode } from '../types';
interface CreateBody {
    path: string;
    title?: string | null;
    tags?: string[];
    mimeType?: string | null;
    inlineText?: string;
}
/**
 * Holds open-tabs state, the active-tab pointer, and the project's full
 * document list for the Cortex view. Persists nothing across reloads in
 * v1 — re-opening a document after reload is one click in the tree.
 *
 * Talks to the general {@code /brain/{tenant}/documents} endpoints — not
 * the ScriptCortex-specific {@code /scripts} endpoints — so Cortex sees
 * all document types in a project, not only scripts.
 *
 * v1 fetches the whole project's document list in one paged call with a
 * large page size. If projects grow past ~500 documents, we'll switch to
 * a tree-friendly endpoint or virtual scrolling.
 */
/**
 * Current text selection inside the active tab's editor. {@code null}
 * when nothing is selected (or the active doc isn't a text doc). The
 * cortex_get_selection client tool reads this; DocumentTabShell writes
 * it via the CodeEditor's {@code selection-changed} emit.
 */
export interface CortexSelection {
    docId: string;
    docPath: string;
    from: number;
    to: number;
    text: string;
}
export declare const useCortexStore: import("pinia").StoreDefinition<"cortex", Pick<{
    projectId: import("vue").Ref<string | null, string | null>;
    files: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[], CortexDocument[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[]>;
    openTabs: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[], CortexDocument[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[]>;
    activeTabId: import("vue").Ref<string | null, string | null>;
    activeTab: import("vue").ComputedRef<CortexDocument | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    fileTree: import("vue").ComputedRef<FolderNode>;
    loadList: (pid: string) => Promise<void>;
    openFile: (id: string) => Promise<void>;
    reloadTab: (id: string) => Promise<void>;
    moveFile: (id: string, newPath: string) => Promise<void>;
    uploadExternalFile: (file: File, folderPath: string) => Promise<CortexDocument>;
    setActiveTab: (id: string) => void;
    closeTab: (id: string) => void;
    updateActiveContent: (text: string) => void;
    saveActive: () => Promise<void>;
    saveTab: (id: string) => Promise<void>;
    saveAllDirty: () => Promise<void>;
    createFile: (body: CreateBody) => Promise<CortexDocument>;
    deleteFile: (id: string) => Promise<void>;
    currentSelection: import("vue").Ref<{
        docId: string;
        docPath: string;
        from: number;
        to: number;
        text: string;
    } | null, CortexSelection | {
        docId: string;
        docPath: string;
        from: number;
        to: number;
        text: string;
    } | null>;
    setSelection: (sel: CortexSelection | null) => void;
    clearSelection: () => void;
}, "projectId" | "error" | "loading" | "files" | "openTabs" | "activeTabId" | "currentSelection">, Pick<{
    projectId: import("vue").Ref<string | null, string | null>;
    files: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[], CortexDocument[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[]>;
    openTabs: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[], CortexDocument[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[]>;
    activeTabId: import("vue").Ref<string | null, string | null>;
    activeTab: import("vue").ComputedRef<CortexDocument | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    fileTree: import("vue").ComputedRef<FolderNode>;
    loadList: (pid: string) => Promise<void>;
    openFile: (id: string) => Promise<void>;
    reloadTab: (id: string) => Promise<void>;
    moveFile: (id: string, newPath: string) => Promise<void>;
    uploadExternalFile: (file: File, folderPath: string) => Promise<CortexDocument>;
    setActiveTab: (id: string) => void;
    closeTab: (id: string) => void;
    updateActiveContent: (text: string) => void;
    saveActive: () => Promise<void>;
    saveTab: (id: string) => Promise<void>;
    saveAllDirty: () => Promise<void>;
    createFile: (body: CreateBody) => Promise<CortexDocument>;
    deleteFile: (id: string) => Promise<void>;
    currentSelection: import("vue").Ref<{
        docId: string;
        docPath: string;
        from: number;
        to: number;
        text: string;
    } | null, CortexSelection | {
        docId: string;
        docPath: string;
        from: number;
        to: number;
        text: string;
    } | null>;
    setSelection: (sel: CortexSelection | null) => void;
    clearSelection: () => void;
}, "activeTab" | "fileTree">, Pick<{
    projectId: import("vue").Ref<string | null, string | null>;
    files: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[], CortexDocument[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[]>;
    openTabs: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[], CortexDocument[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        kind?: (string | null) | undefined;
        inlineText: string;
        dirty: boolean;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
    }[]>;
    activeTabId: import("vue").Ref<string | null, string | null>;
    activeTab: import("vue").ComputedRef<CortexDocument | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    fileTree: import("vue").ComputedRef<FolderNode>;
    loadList: (pid: string) => Promise<void>;
    openFile: (id: string) => Promise<void>;
    reloadTab: (id: string) => Promise<void>;
    moveFile: (id: string, newPath: string) => Promise<void>;
    uploadExternalFile: (file: File, folderPath: string) => Promise<CortexDocument>;
    setActiveTab: (id: string) => void;
    closeTab: (id: string) => void;
    updateActiveContent: (text: string) => void;
    saveActive: () => Promise<void>;
    saveTab: (id: string) => Promise<void>;
    saveAllDirty: () => Promise<void>;
    createFile: (body: CreateBody) => Promise<CortexDocument>;
    deleteFile: (id: string) => Promise<void>;
    currentSelection: import("vue").Ref<{
        docId: string;
        docPath: string;
        from: number;
        to: number;
        text: string;
    } | null, CortexSelection | {
        docId: string;
        docPath: string;
        from: number;
        to: number;
        text: string;
    } | null>;
    setSelection: (sel: CortexSelection | null) => void;
    clearSelection: () => void;
}, "clearSelection" | "loadList" | "openFile" | "reloadTab" | "moveFile" | "uploadExternalFile" | "setActiveTab" | "closeTab" | "updateActiveContent" | "saveActive" | "saveTab" | "saveAllDirty" | "createFile" | "deleteFile" | "setSelection">>;
export {};
//# sourceMappingURL=cortexStore.d.ts.map