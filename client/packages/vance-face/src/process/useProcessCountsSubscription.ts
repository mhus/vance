import { onBeforeUnmount, watch, type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';
import type { ProcessCountsNotification } from '@vance/generated';
import { resetProcessCounts, setProcessCounts } from './processCountsStore';

/**
 * Wire the {@code process-counts} subscription into
 * {@link processCountsStore}. Mounted once from EditorShell, so every editor
 * with a WebSocket gets the topbar badge for free.
 *
 * <p>Follows the socket ref across reconnects (the instance is swapped on
 * rebind) and resets the counts when the socket goes away — a badge from a
 * session we are no longer bound to would be a lie. The server re-pushes the
 * current numbers on welcome/resume, so the reset is never a permanent gap.
 *
 * <p>Requirement: planning/process-visibility.md §4.A
 */
export function useProcessCountsSubscription(socketRef: Ref<BrainWsApi | null>): void {
  let unsub: (() => void) | null = null;

  function detach(): void {
    if (unsub) {
      try { unsub(); } catch { /* ignore */ }
      unsub = null;
    }
  }

  function attach(socket: BrainWsApi): void {
    detach();
    unsub = socket.on<ProcessCountsNotification>('process-counts', (data) => {
      if (data) setProcessCounts(data);
    });
  }

  watch(
    socketRef,
    (next) => {
      if (next) {
        attach(next);
      } else {
        detach();
        resetProcessCounts();
      }
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    detach();
    resetProcessCounts();
  });
}
