import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
export declare const useDocumentRefStore: import("pinia").StoreDefinition<"documentRef", Pick<{
    currentProject: import("vue").Ref<string, string>;
    setCurrentProject: (projectName: string) => void;
    resolve: (embedRef: EmbedRef) => Promise<DocumentDto>;
    invalidate: (projectName: string, path: string) => void;
    clear: () => void;
}, "currentProject">, Pick<{
    currentProject: import("vue").Ref<string, string>;
    setCurrentProject: (projectName: string) => void;
    resolve: (embedRef: EmbedRef) => Promise<DocumentDto>;
    invalidate: (projectName: string, path: string) => void;
    clear: () => void;
}, never>, Pick<{
    currentProject: import("vue").Ref<string, string>;
    setCurrentProject: (projectName: string) => void;
    resolve: (embedRef: EmbedRef) => Promise<DocumentDto>;
    invalidate: (projectName: string, path: string) => void;
    clear: () => void;
}, "clear" | "setCurrentProject" | "resolve" | "invalidate">>;
//# sourceMappingURL=documentRefStore.d.ts.map