import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue';
import { useWsConnection } from '@/ws/wsConnectionStore';
import type { DocumentInvalidateNotification } from '@vance/generated';

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

export function useDocumentInvalidate(
  options: DocumentInvalidateOptions,
): DocumentInvalidateReaction {
  const { socket } = useWsConnection();
  const debounceMs = options.debounceMs ?? 500;
  const activityWindowMs = options.activityWindowMs ?? 1500;

  // Wall-clock of the most recent invalidate (any docId).
  const lastInvalidateAt = ref<number>(0);
  // Trailing-debounce timer per docId.
  const timers = new Map<string, ReturnType<typeof setTimeout>>();
  // WS-listener unsubscribe handle, refreshed on socket replacement.
  let unsubscribeWs: (() => void) | null = null;

  function scheduleApply(docId: string, kind: string): void {
    const existing = timers.get(docId);
    if (existing) clearTimeout(existing);
    const timer = setTimeout(async () => {
      timers.delete(docId);
      try {
        await options.apply(docId, kind);
      } catch (e) {
        console.warn(`[document-invalidate] apply for doc='${docId}' threw:`, e);
      }
    }, debounceMs);
    timers.set(docId, timer);
  }

  function attach(): void {
    detach();
    const sock = socket.value;
    if (!sock) return;
    unsubscribeWs = sock.on<DocumentInvalidateNotification>(
      'document-invalidate',
      (data) => {
        if (!data || !data.documentId) return;
        lastInvalidateAt.value = Date.now();
        if (!options.openDocumentIds.value.includes(data.documentId)) return;
        scheduleApply(data.documentId, data.kind ?? 'body');
      },
    );
  }

  function detach(): void {
    if (unsubscribeWs) {
      try { unsubscribeWs(); } catch { /* ignore */ }
      unsubscribeWs = null;
    }
  }

  watch(socket, () => attach(), { immediate: true });

  // Polling-based "is the agent actively editing" — re-evaluates every
  // 500ms so the pulse animation snaps off ~at the right time after the
  // activity window expires. Cheap, no WebSocket round-trip needed.
  const nowTick = ref<number>(Date.now());
  const tickInterval = setInterval(() => { nowTick.value = Date.now(); }, 500);
  const isAgentEditing = computed<boolean>(
    () => nowTick.value - lastInvalidateAt.value < activityWindowMs,
  );

  onBeforeUnmount(() => {
    detach();
    for (const t of timers.values()) clearTimeout(t);
    timers.clear();
    clearInterval(tickInterval);
  });

  return { isAgentEditing };
}
