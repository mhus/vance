/**
 * In-memory representation of a document open in Cortex. Mirrors the
 * server's DocumentDto, plus a {@code dirty} flag tracking unsaved
 * edits in the current tab.
 */
export interface CortexDocument {
    id: string;
    path: string;
    name: string;
    title?: string | null;
    mimeType?: string | null;
    /**
     * Document kind from {@code DocumentDto.kind} — e.g. "list",
     * "checklist", "tree", "records", "sheet", "chart", "graph", "image".
     * The DocumentTabShell uses {@code (kind, mimeType)} pairs to pick a
     * typed-model renderer; absent / unknown kinds fall back to the
     * Code-Tab.
     */
    kind?: string | null;
    inlineText: string;
    /** True when {@link inlineText} has been edited since load/save. */
    dirty: boolean;
}
/**
 * Folder-tree node, built client-side by aggregating the path prefixes
 * of every document in the project.
 */
export interface FolderNode {
    /** Full path prefix, e.g. {@code "utils/math"} */
    path: string;
    /** Last path segment, e.g. {@code "math"} */
    name: string;
    /** Direct sub-folders. */
    children: FolderNode[];
    /** Files directly inside this folder. */
    files: CortexDocument[];
}
//# sourceMappingURL=types.d.ts.map