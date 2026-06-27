/**
 * View / Edit toggle state, shared across the Cortex / Notepad editor
 * shell. Lives at module scope so {@link DocumentTabShell} (writes via
 * the toolbar toggle) and {@link EditorApp} (reads to gate App-mode
 * shell trimming) see the same reactive value without prop drilling.
 *
 * <p>v1 keeps the flag global across all tabs — the sessionStorage
 * persistence pattern from the original local ref is retained so a
 * user who prefers 'edit' keeps it across doc switches and tab opens.
 *
 * <ul>
 *   <li><b>view</b> — the kind's rendered component (the App, the
 *       Markdown HTML, the Mermaid diagram, …). Default.</li>
 *   <li><b>edit</b> — the raw CodeEditor on the document's inlineText,
 *       same component the catch-all 'code' binding uses.</li>
 * </ul>
 */
export type ViewEditMode = 'view' | 'edit';
export declare function useViewEditMode(): import("vue").Ref<ViewEditMode, ViewEditMode>;
//# sourceMappingURL=useViewEditMode.d.ts.map