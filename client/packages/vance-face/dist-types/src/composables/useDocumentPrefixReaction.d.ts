import { type Ref } from 'vue';
import type { DocumentChangedNotification } from '@vance/generated';
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
export declare function useDocumentPrefixReaction(options: {
    prefix: Ref<string | null>;
    onRemoteChange: (path: string, notification: DocumentChangedNotification) => void;
}): void;
//# sourceMappingURL=useDocumentPrefixReaction.d.ts.map