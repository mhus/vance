export interface DocumentDraft {
    /** Suggested document title — typically the source's headline. */
    title?: string;
    /** Suggested file path inside the project, e.g. `notes/from-inbox-XYZ.md`. */
    path?: string;
    /** Body content — typically Markdown. */
    content?: string;
    /** MIME type — defaults to `text/markdown` when missing. */
    mimeType?: string;
    /** Free-form provenance string for the UI to render
     *  ("From inbox-item «Title» of 2026-04-28 09:30"). */
    source?: string;
}
/** Persist a draft for the next editor that opens with `?createDraft=1`. */
export declare function setDocumentDraft(draft: DocumentDraft): void;
/**
 * Read and immediately remove the draft. Returns `null` if none is
 * set or the stored payload is unparsable. The one-shot semantics
 * avoid stale prefills after a refresh / back-nav.
 */
export declare function consumeDocumentDraft(): DocumentDraft | null;
//# sourceMappingURL=documentDraft.d.ts.map