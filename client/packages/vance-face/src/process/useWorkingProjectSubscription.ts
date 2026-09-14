import { onBeforeUnmount, watch, type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';
import type { WorkingProjectNotification } from '@vance/generated';
import { resetWorkingProject, setWorkingProject } from './workingProjectStore';

/**
 * Wire the {@code working-project-changed} subscription into
 * {@link workingProjectStore}. Mounted once from EditorShell, so every
 * editor with a WebSocket gets the topbar badge for free.
 *
 * <p>Follows the socket ref across reconnects (the instance is swapped on
 * rebind) and resets the spot when the socket goes away — a badge from a
 * session we are no longer bound to would be a lie. The server re-pushes
 * the current spot on welcome/resume, so the reset is never a permanent
 * gap.
 */
export function useWorkingProjectSubscription(socketRef: Ref<BrainWsApi | null>): void {
  let unsub: (() => void) | null = null;

  function detach(): void {
    if (unsub) {
      try { unsub(); } catch { /* ignore */ }
      unsub = null;
    }
  }

  function attach(socket: BrainWsApi): void {
    detach();
    unsub = socket.on<WorkingProjectNotification>('working-project-changed', (data) => {
      if (data) setWorkingProject(data);
    });
  }

  watch(
    socketRef,
    (next) => {
      if (next) {
        attach(next);
      } else {
        detach();
        resetWorkingProject();
      }
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    detach();
    resetWorkingProject();
  });
}
