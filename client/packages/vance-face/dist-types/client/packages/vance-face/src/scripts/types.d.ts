/**
 * In-memory file representation for the Script Cortex editor.
 * Mirrors what the server returns as DocumentDto, plus a {@code dirty}
 * flag that tracks unsaved edits in the current tab.
 */
export interface ScriptFile {
    id: string;
    path: string;
    name: string;
    title?: string | null;
    mimeType?: string | null;
    inlineText: string;
    /** Server-side hash of the content the last deep-review was based on. */
    lastDeepReviewedHash?: string | null;
    /** JSON-serialized warning list from the last review. */
    lastDeepReviewWarningsJson?: string | null;
    lastDeepReviewedAtMs?: number | null;
    /** True when {@link inlineText} has been edited since load/save. */
    dirty: boolean;
}
/**
 * Folder-tree node. Built client-side by aggregating the path-prefixes
 * of every script in the project.
 */
export interface FolderNode {
    /** Full path prefix, e.g. {@code "utils/math"} */
    path: string;
    /** Last path segment, e.g. {@code "math"} */
    name: string;
    /** Direct sub-folders. */
    children: FolderNode[];
    /** Files directly inside this folder. */
    files: ScriptFile[];
}
//# sourceMappingURL=types.d.ts.map