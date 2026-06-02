import { type Ref } from 'vue';
/**
 * One-shot fetcher for a single workspace file. The Brain serves raw
 * bytes; we treat known text-ish extensions as text and surface them
 * to a `<CodeEditor>`/`<MarkdownView>`. Binary content stays a URL —
 * the caller's `<img>` (cookie auth attaches automatically on
 * same-origin) or `<a download>` consumes it.
 */
export type RenderMode = 'markdown' | 'text' | 'image' | 'binary';
export interface FileLoadResult {
    /** Resolved render mode based on filename extension. */
    mode: RenderMode;
    /** Text payload — populated only when {@link mode} is `'text'` or `'markdown'`. */
    text: string | null;
    /** Stable URL for `<img src>` / `<a href download>` use. */
    url: string;
    /** MIME-type hint for `<CodeEditor>` syntax highlighting. */
    mimeType: string;
}
interface UseWorkspaceFile {
    result: Ref<FileLoadResult | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (projectId: string, path: string, name: string) => Promise<void>;
    clear: () => void;
}
export declare function workspaceFileUrl(projectId: string, path: string): string;
export declare function useWorkspaceFile(): UseWorkspaceFile;
export {};
//# sourceMappingURL=useWorkspaceFile.d.ts.map