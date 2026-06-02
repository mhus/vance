/**
 * Cross-editor draft handed from a sending editor (e.g. Inbox) to the
 * Document editor's create-modal. Read-once: the consumer removes it
 * from `localStorage` immediately so navigating back doesn't
 * re-trigger the prefill.
 *
 * Web-only concept. The Multi-Page-App layout means each editor is a
 * separate HTML page with its own JavaScript context; `localStorage`
 * is the only handover channel that survives the navigation. Mobile
 * uses navigation parameters instead and does not need this module.
 */
const DOCUMENT_DRAFT_KEY = 'vance.documentDraft';
/** Persist a draft for the next editor that opens with `?createDraft=1`. */
export function setDocumentDraft(draft) {
    try {
        window.localStorage.setItem(DOCUMENT_DRAFT_KEY, JSON.stringify(draft));
    }
    catch {
        // Best-effort — Safari private mode can throw on storage writes.
    }
}
/**
 * Read and immediately remove the draft. Returns `null` if none is
 * set or the stored payload is unparsable. The one-shot semantics
 * avoid stale prefills after a refresh / back-nav.
 */
export function consumeDocumentDraft() {
    let raw = null;
    try {
        raw = window.localStorage.getItem(DOCUMENT_DRAFT_KEY);
        if (raw !== null) {
            window.localStorage.removeItem(DOCUMENT_DRAFT_KEY);
        }
    }
    catch {
        return null;
    }
    if (raw === null)
        return null;
    try {
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === 'object') {
            return parsed;
        }
    }
    catch {
        // Corrupted entry — drop silently, already removed above.
    }
    return null;
}
//# sourceMappingURL=documentDraft.js.map