import { onBeforeUnmount, watch, type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';
import type { NotificationDto } from '@vance/generated';
import { useNotificationStore } from './notificationStore';

/**
 * Wire the {@code notify} WebSocket subscription into the global
 * {@link useNotificationStore}. Host components (ChatApp, Cortex's
 * chat panel) call this with their {@code ref<socket | null>} so
 * the subscription follows reconnects (the socket can be swapped
 * for a fresh instance on reconnect / session-rebind).
 *
 * <p>Spec: specification/user-notification-channel.md
 */
export function useNotificationSubscription(
  socketRef: Ref<BrainWsApi | null>,
): void {
  const store = useNotificationStore();
  let unsub: (() => void) | null = null;

  function detach(): void {
    if (unsub) {
      try { unsub(); } catch { /* ignore */ }
      unsub = null;
    }
  }

  function attach(socket: BrainWsApi): void {
    detach();
    unsub = socket.on<NotificationDto>('notify', (data) => {
      if (data) store.push(data);
    });
  }

  watch(
    socketRef,
    (next) => {
      if (next) attach(next);
      else detach();
    },
    { immediate: true },
  );

  onBeforeUnmount(detach);
}
