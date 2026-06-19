import { onBeforeUnmount, ref, watch, type Ref } from 'vue';
import { onDocumentChanged } from '@/ws/wsConnectionStore';

/**
 * Generic "react to a remote document change" composable, shared by
 * every editor surface (DocumentApp, Cortex tabs, future surfaces). It
 * owns three things:
 *
 * <ol>
 *   <li>Subscribing to the {@code documents.changed} WS frame for the
 *       given path and swapping the subscription when the path
 *       changes.</li>
 *   <li>Dispatching the change through the editor's {@link Options#tryApply}
 *       hook. The editor decides whether it can absorb the change
 *       silently (clean buffer, non-AV-binary, …) or needs the user to
 *       decide.</li>
 *   <li>Exposing a reactive {@link Reaction#pendingChange} flag plus
 *       {@code keepLocal}/{@code acceptRemote} actions so the editor's
 *       template can render a banner.</li>
 * </ol>
 *
 * <p>The editor's {@code tryApply(kind)} returns:
 * <ul>
 *   <li>{@code true} — applied the change silently; the composable
 *       leaves {@code pendingChange} as {@code null}.</li>
 *   <li>{@code false} — refused to apply (dirty buffer, audio in
 *       playback, …); the composable raises {@code pendingChange = kind}
 *       so the editor surfaces a banner.</li>
 * </ul>
 *
 * <p>{@code forceApply(kind)} runs the same effect as {@code tryApply}'s
 * happy path but unconditionally — driven by the user clicking
 * "Remote übernehmen". The editor must persist whatever state it needs
 * to make the apply succeed regardless of dirty/playback flags.
 *
 * <p>See {@code planning/document-presence.md} §"Phase A".
 */
export interface DocumentChangeReactionOptions {
  /** Path to watch. Reactive: switching the value swaps the subscription. */
  path: Ref<string | null>;
  /**
   * Editor's silent-apply attempt. Receives the wire {@code kind}
   * ({@code "upserted"} / {@code "deleted"}). Return {@code true} when
   * the change was applied without user interaction, {@code false} to
   * raise the conflict banner.
   */
  tryApply: (kind: string) => Promise<boolean>;
  /**
   * Unconditional apply — invoked when the user clicks "Remote
   * übernehmen" on the banner. Overrides any dirty/playback guard the
   * editor enforces in {@link tryApply}.
   */
  forceApply: (kind: string) => Promise<void>;
}

export interface DocumentChangeReaction {
  /**
   * The kind of the pending change ({@code "upserted"} /
   * {@code "deleted"}) when a banner should be shown. {@code null} when
   * no banner is needed (no change pending, or the change was applied
   * silently).
   */
  pendingChange: Ref<string | null>;
  /** Banner action: dismiss without applying — user keeps local edits. */
  keepLocal: () => void;
  /**
   * Banner action: apply the remote state, discarding any local edits.
   * Calls {@link DocumentChangeReactionOptions.forceApply}.
   */
  acceptRemote: () => Promise<void>;
}

export function useDocumentChangeReaction(
  options: DocumentChangeReactionOptions,
): DocumentChangeReaction {
  const pendingChange: Ref<string | null> = ref(null);
  let unsubscribe: (() => void) | null = null;

  watch(
    options.path,
    (path) => {
      pendingChange.value = null;
      if (unsubscribe) {
        try { unsubscribe(); } catch { /* ignore */ }
        unsubscribe = null;
      }
      if (!path) return;
      unsubscribe = onDocumentChanged(path, async (kind) => {
        try {
          const handled = await options.tryApply(kind);
          if (!handled) {
            pendingChange.value = kind;
          }
        } catch (e) {
          // Any error in the editor's silent path falls through to the
          // banner so the user can decide manually rather than losing
          // the change altogether.
          console.warn('[documents.changed] tryApply threw, falling back to banner:', e);
          pendingChange.value = kind;
        }
      });
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    if (unsubscribe) {
      try { unsubscribe(); } catch { /* ignore */ }
      unsubscribe = null;
    }
  });

  function keepLocal(): void {
    pendingChange.value = null;
  }

  async function acceptRemote(): Promise<void> {
    const kind = pendingChange.value;
    pendingChange.value = null;
    if (!kind) return;
    try {
      await options.forceApply(kind);
    } catch (e) {
      // Same posture as tryApply — surface the banner again so the
      // user can retry.
      console.warn('[documents.changed] forceApply threw:', e);
      pendingChange.value = kind;
    }
  }

  return { pendingChange, keepLocal, acceptRemote };
}

/** True for {@code audio/*} or {@code video/*}. */
export function isAudioVideoMime(mime: string | null | undefined): boolean {
  if (!mime) return false;
  return mime.startsWith('audio/') || mime.startsWith('video/');
}
