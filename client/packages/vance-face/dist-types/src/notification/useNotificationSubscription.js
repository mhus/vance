import { onBeforeUnmount, watch } from 'vue';
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
export function useNotificationSubscription(socketRef) {
    const store = useNotificationStore();
    let unsub = null;
    function detach() {
        if (unsub) {
            try {
                unsub();
            }
            catch { /* ignore */ }
            unsub = null;
        }
    }
    function attach(socket) {
        detach();
        unsub = socket.on('notify', (data) => {
            if (data)
                store.push(data);
        });
    }
    watch(socketRef, (next) => {
        if (next)
            attach(next);
        else
            detach();
    }, { immediate: true });
    onBeforeUnmount(detach);
}
//# sourceMappingURL=useNotificationSubscription.js.map