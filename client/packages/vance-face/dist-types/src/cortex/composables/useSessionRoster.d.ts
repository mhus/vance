import { type Ref } from 'vue';
import type { SessionParticipantDto } from '@vance/generated';
/**
 * Reactive participant roster for a multi-user session — see
 * {@code planning/multi-user-sessions.md} §7.
 *
 * <p>Subscribes to the {@code session-roster} server-push frames on
 * the brain WebSocket. The brain pushes a fresh roster every time a
 * client joins/leaves the session, so this ref always reflects the
 * current participant list.
 *
 * <p>Solo sessions (1 connection) still receive roster frames after
 * register/unregister; the participants array has length 1 in that
 * case. UI components decide whether to show themselves.
 *
 * <p>{@code sessionId} can be a {@link Ref} so the same composable
 * follows session switches in the Cortex chat-bound view.
 *
 * <p>Returns reactive {@code participants} plus an
 * {@code onChange(handler)} hook that fires per join / leave delta
 * — non-persistent ephemeral activity feed.
 */
export interface RosterChange {
    joined: SessionParticipantDto[];
    left: SessionParticipantDto[];
    at: Date;
}
export declare function useSessionRoster(sessionId: Ref<string | null>): {
    participants: Ref<{
        editorId: string;
        userId: string;
        displayName?: string | undefined;
    }[], SessionParticipantDto[] | {
        editorId: string;
        userId: string;
        displayName?: string | undefined;
    }[]>;
    onChange: (handler: (change: RosterChange) => void) => () => void;
    onInitial: (handler: (participants: SessionParticipantDto[]) => void) => () => void;
};
//# sourceMappingURL=useSessionRoster.d.ts.map