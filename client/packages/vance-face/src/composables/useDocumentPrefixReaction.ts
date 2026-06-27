import { onBeforeUnmount, watch, type Ref } from 'vue';
import type { DocumentChangedNotification } from '@vance/generated';
import {
  onDocumentChangedPrefix,
  subscribeDocumentPrefix,
  unsubscribeDocumentPrefix,
} from '@/ws/wsConnectionStore';

/**
 * Live-watch a folder prefix. Sends {@code documents.subscribePrefix}
 * to the brain on mount (and on every prefix change) and invokes
 * {@code onRemoteChange} for every {@code documents.changed} frame whose
 * path starts with the prefix.
 *
 * <p>The prefix MUST end with {@code /} — the server rejects anything
 * else. Folder-bound apps (Calendar, Kanban, Slideshow, …) typically
 * pass {@code `${appFolder}/`}.
 *
 * <p>Self-writes are filtered server-side via the writer's {@code editorId}
 * (same mechanism as the single-path
 * {@link useDocumentChangeReaction} composable), so this handler only
 * sees changes from other connections.
 *
 * <p>Manifest-change handling: the same change-frame that drives this
 * composable also fires the tab's own single-path subscription on the
 * {@code _app.yaml} manifest. To avoid double-handling the manifest path
 * the caller should typically early-return in the handler when
 * {@code path === manifestPath}.
 *
 * @param options.prefix Reactive ref of the prefix to watch. {@code null}
 *   suspends the subscription.
 * @param options.onRemoteChange Called for each matching change frame.
 */
export function useDocumentPrefixReaction(options: {
  prefix: Ref<string | null>;
  onRemoteChange: (path: string, notification: DocumentChangedNotification) => void;
}): void {
  let activePrefix: string | null = null;
  let unsubscribeListener: (() => void) | null = null;

  function teardown(): void {
    if (unsubscribeListener) {
      try { unsubscribeListener(); } catch { /* noop */ }
      unsubscribeListener = null;
    }
    if (activePrefix) {
      void unsubscribeDocumentPrefix(activePrefix).catch(() => {
        /* server-side already cleaned on socket close — fine to ignore */
      });
      activePrefix = null;
    }
  }

  watch(
    options.prefix,
    (next) => {
      teardown();
      if (!next) return;
      activePrefix = next;
      unsubscribeListener = onDocumentChangedPrefix(next, (notification) => {
        options.onRemoteChange(notification.path, notification);
      });
      void subscribeDocumentPrefix(next).catch((e) => {
        // Connection not ready or rejected — log and let
        // Reconnect-Resubscribe pick it up on the next socket.
        console.warn(`[useDocumentPrefixReaction] subscribePrefix '${next}' failed:`, e);
      });
    },
    { immediate: true },
  );

  onBeforeUnmount(teardown);
}
