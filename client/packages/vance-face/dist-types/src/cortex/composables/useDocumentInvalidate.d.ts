import { type Ref } from 'vue';
/**
 * Session-channel handler for {@code document-invalidate} frames. Sent
 * by the brain when a server-side tool (doc_write / doc_edit / doc_append
 * / doc_replace_lines / doc_note_*) mutated a document on behalf of the
 * bound session.
 *
 * <p>Functionally redundant with the documents-channel
 * {@code documents.changed} push when Redis is enabled and the user's
 * pod is the home-pod — but works without Redis and across pods because
 * the frame rides the existing chat-WS tunnel. ETag-based caching on the
 * GET-content path makes the duplicate-trigger case idempotent.
 *
 * <p>See {@code planning/cortex-document-invalidation.md}.
 */
export interface DocumentInvalidateOptions {
    /** Set of currently-open document ids (reactive). Frames for other docs are ignored. */
    openDocumentIds: Ref<string[]>;
    /**
     * Editor's apply-callback. Called after the debounce window has settled.
     * Receives the documentId and the kind ({@code "body"} / {@code "notes"})
     * — most editors will ignore the kind and just reload the whole doc.
     */
    apply: (documentId: string, kind: string) => Promise<void>;
    /** Debounce window in ms (default 500). */
    debounceMs?: number;
    /**
     * Activity-window in ms — how long after the last frame the
     * {@link DocumentInvalidateReaction#isAgentEditing} flag stays true.
     * Default 1500.
     */
    activityWindowMs?: number;
}
export interface DocumentInvalidateReaction {
    /**
     * True while invalidate frames have been arriving recently. Drives the
     * topbar "AI editing…" pulse. Re-evaluates every 500ms via an internal
     * setInterval; consumers don't need to do anything.
     */
    isAgentEditing: Ref<boolean>;
}
export declare function useDocumentInvalidate(options: DocumentInvalidateOptions): DocumentInvalidateReaction;
//# sourceMappingURL=useDocumentInvalidate.d.ts.map