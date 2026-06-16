import { type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';
/**
 * Wire the {@code notify} WebSocket subscription into the global
 * {@link useNotificationStore}. Host components (ChatApp, Cortex's
 * chat panel) call this with their {@code ref<socket | null>} so
 * the subscription follows reconnects (the socket can be swapped
 * for a fresh instance on reconnect / session-rebind).
 *
 * <p>Spec: specification/user-notification-channel.md
 */
export declare function useNotificationSubscription(socketRef: Ref<BrainWsApi | null>): void;
//# sourceMappingURL=useNotificationSubscription.d.ts.map