import { type Ref } from 'vue';
import type { NotificationDto } from '@vance/generated';
export interface Toast {
    /** Stable id for the {@code <TransitionGroup>} key. */
    id: string;
    notification: NotificationDto;
    /** Wall-clock when the toast was added (drives auto-dismiss timer). */
    addedAt: number;
}
/**
 * Composable-shaped accessor mirroring the Pinia call site so the
 * consumer code reads the same whether the backing store is Pinia or a
 * plain reactive singleton.
 */
export declare function useNotificationStore(): {
    toasts: Ref<Toast[]>;
    push: (n: NotificationDto) => void;
    dismiss: (id: string) => void;
};
//# sourceMappingURL=notificationStore.d.ts.map