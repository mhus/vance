import type { ScriptFile, FolderNode } from '../types';
interface CreateBody {
    path: string;
    title?: string | null;
    tags?: string[];
    mimeType?: string | null;
    inlineText?: string;
}
/**
 * Holds the open-tabs state, the active-tab pointer and the project's
 * full file list. Persists nothing across reloads in v1 — re-opening
 * a script after reload is one click in the tree.
 */
export declare const useScriptStore: import("pinia").StoreDefinition<"scriptCortex", Pick<{
    projectId: import("vue").Ref<string | null, string | null>;
    files: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[], ScriptFile[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[]>;
    openTabs: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[], ScriptFile[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[]>;
    activeTabId: import("vue").Ref<string | null, string | null>;
    activeTab: import("vue").ComputedRef<ScriptFile | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    fileTree: import("vue").ComputedRef<FolderNode>;
    loadList: (pid: string) => Promise<void>;
    openFile: (id: string) => Promise<void>;
    setActiveTab: (id: string) => void;
    closeTab: (id: string) => void;
    updateActiveContent: (text: string) => void;
    saveActive: () => Promise<void>;
    createFile: (body: CreateBody) => Promise<ScriptFile>;
    deleteFile: (id: string) => Promise<void>;
}, "error" | "loading" | "projectId" | "files" | "openTabs" | "activeTabId">, Pick<{
    projectId: import("vue").Ref<string | null, string | null>;
    files: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[], ScriptFile[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[]>;
    openTabs: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[], ScriptFile[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[]>;
    activeTabId: import("vue").Ref<string | null, string | null>;
    activeTab: import("vue").ComputedRef<ScriptFile | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    fileTree: import("vue").ComputedRef<FolderNode>;
    loadList: (pid: string) => Promise<void>;
    openFile: (id: string) => Promise<void>;
    setActiveTab: (id: string) => void;
    closeTab: (id: string) => void;
    updateActiveContent: (text: string) => void;
    saveActive: () => Promise<void>;
    createFile: (body: CreateBody) => Promise<ScriptFile>;
    deleteFile: (id: string) => Promise<void>;
}, "activeTab" | "fileTree">, Pick<{
    projectId: import("vue").Ref<string | null, string | null>;
    files: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[], ScriptFile[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[]>;
    openTabs: import("vue").Ref<{
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[], ScriptFile[] | {
        id: string;
        path: string;
        name: string;
        title?: (string | null) | undefined;
        mimeType?: (string | null) | undefined;
        inlineText: string;
        lastDeepReviewedHash?: (string | null) | undefined;
        lastDeepReviewWarningsJson?: (string | null) | undefined;
        lastDeepReviewedAtMs?: (number | null) | undefined;
        dirty: boolean;
    }[]>;
    activeTabId: import("vue").Ref<string | null, string | null>;
    activeTab: import("vue").ComputedRef<ScriptFile | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    fileTree: import("vue").ComputedRef<FolderNode>;
    loadList: (pid: string) => Promise<void>;
    openFile: (id: string) => Promise<void>;
    setActiveTab: (id: string) => void;
    closeTab: (id: string) => void;
    updateActiveContent: (text: string) => void;
    saveActive: () => Promise<void>;
    createFile: (body: CreateBody) => Promise<ScriptFile>;
    deleteFile: (id: string) => Promise<void>;
}, "openFile" | "deleteFile" | "loadList" | "setActiveTab" | "closeTab" | "updateActiveContent" | "saveActive" | "createFile">>;
export {};
//# sourceMappingURL=scriptStore.d.ts.map