import type { NotificationDto, NotificationSeverity } from '@vance/generated';
export interface Toast {
    /** Stable id for the {@code <TransitionGroup>} key. */
    id: string;
    notification: NotificationDto;
    /** Wall-clock when the toast was added (drives auto-dismiss timer). */
    addedAt: number;
}
export declare const useNotificationStore: import("pinia").StoreDefinition<"notification", Pick<{
    toasts: import("vue").Ref<{
        id: string;
        notification: {
            text: string;
            severity: NotificationSeverity;
            emittedAt?: Date | undefined;
            sourceProcessId?: string | undefined;
            sourceProcessName?: string | undefined;
            sourceProcessTitle?: string | undefined;
            sessionId?: string | undefined;
        };
        addedAt: number;
    }[], Toast[] | {
        id: string;
        notification: {
            text: string;
            severity: NotificationSeverity;
            emittedAt?: Date | undefined;
            sourceProcessId?: string | undefined;
            sourceProcessName?: string | undefined;
            sourceProcessTitle?: string | undefined;
            sessionId?: string | undefined;
        };
        addedAt: number;
    }[]>;
    push: (notification: NotificationDto) => void;
    dismiss: (id: string) => void;
}, "toasts">, Pick<{
    toasts: import("vue").Ref<{
        id: string;
        notification: {
            text: string;
            severity: NotificationSeverity;
            emittedAt?: Date | undefined;
            sourceProcessId?: string | undefined;
            sourceProcessName?: string | undefined;
            sourceProcessTitle?: string | undefined;
            sessionId?: string | undefined;
        };
        addedAt: number;
    }[], Toast[] | {
        id: string;
        notification: {
            text: string;
            severity: NotificationSeverity;
            emittedAt?: Date | undefined;
            sourceProcessId?: string | undefined;
            sourceProcessName?: string | undefined;
            sourceProcessTitle?: string | undefined;
            sessionId?: string | undefined;
        };
        addedAt: number;
    }[]>;
    push: (notification: NotificationDto) => void;
    dismiss: (id: string) => void;
}, never>, Pick<{
    toasts: import("vue").Ref<{
        id: string;
        notification: {
            text: string;
            severity: NotificationSeverity;
            emittedAt?: Date | undefined;
            sourceProcessId?: string | undefined;
            sourceProcessName?: string | undefined;
            sourceProcessTitle?: string | undefined;
            sessionId?: string | undefined;
        };
        addedAt: number;
    }[], Toast[] | {
        id: string;
        notification: {
            text: string;
            severity: NotificationSeverity;
            emittedAt?: Date | undefined;
            sourceProcessId?: string | undefined;
            sourceProcessName?: string | undefined;
            sourceProcessTitle?: string | undefined;
            sessionId?: string | undefined;
        };
        addedAt: number;
    }[]>;
    push: (notification: NotificationDto) => void;
    dismiss: (id: string) => void;
}, "push" | "dismiss">>;
//# sourceMappingURL=notificationStore.d.ts.map