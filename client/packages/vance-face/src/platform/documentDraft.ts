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
export function setDocumentDraft(draft: DocumentDraft): void {
  try {
    window.localStorage.setItem(DOCUMENT_DRAFT_KEY, JSON.stringify(draft));
  } catch {
    // Best-effort — Safari private mode can throw on storage writes.
  }
}

/**
 * Read and immediately remove the draft. Returns `null` if none is
 * set or the stored payload is unparsable. The one-shot semantics
 * avoid stale prefills after a refresh / back-nav.
 */
export function consumeDocumentDraft(): DocumentDraft | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(DOCUMENT_DRAFT_KEY);
    if (raw !== null) {
      window.localStorage.removeItem(DOCUMENT_DRAFT_KEY);
    }
  } catch {
    return null;
  }
  if (raw === null) return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === 'object') {
      return parsed as DocumentDraft;
    }
  } catch {
    // Corrupted entry — drop silently, already removed above.
  }
  return null;
}
