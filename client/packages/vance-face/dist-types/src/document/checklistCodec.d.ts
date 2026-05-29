export type ChecklistStatus = 'open' | 'done' | 'in_progress' | 'review' | 'blocked' | 'needs_info' | 'deferred' | 'delegated' | 'waiting';
export type ChecklistPriority = 'high' | 'low';
/** Reserved per-item extra key that preserves a non-standard Markdown
 *  checkbox char across a round-trip. */
export declare const STATUS_CHAR_EXTRA_KEY = "_statusChar";
export interface ChecklistItem {
    text: string;
    status: ChecklistStatus;
    priority?: ChecklistPriority;
    /** Unknown fields the editor doesn't recognise. Re-emitted on save. */
    extra: Record<string, unknown>;
}
export interface ChecklistDocument {
    /** Always `'checklist'` for checklist documents. */
    kind: string;
    items: ChecklistItem[];
    /** Unknown top-level fields. For markdown the residual front-matter. */
    extra: Record<string, unknown>;
}
export declare class ChecklistCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseChecklist(body: string, mimeType: string): ChecklistDocument;
export declare function serializeChecklist(doc: ChecklistDocument, mimeType: string): string;
export declare function isChecklistMime(mimeType: string | null | undefined): boolean;
//# sourceMappingURL=checklistCodec.d.ts.map