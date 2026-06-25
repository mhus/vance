import { onBeforeUnmount, ref, watch, type Ref } from 'vue';
import type { SessionParticipantDto, SessionRosterData } from '@vance/generated';
import { useWsConnection } from '@/ws/wsConnectionStore';

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
 */
export function useSessionRoster(sessionId: Ref<string | null>) {
  const participants = ref<SessionParticipantDto[]>([]);
  const conn = useWsConnection();

  let unsubscribeRoster: (() => void) | null = null;

  function attach() {
    detach();
    const socket = conn.socket.value;
    if (!socket) return;
    unsubscribeRoster = socket.on<SessionRosterData>('session-roster', (data) => {
      if (!data || data.sessionId !== sessionId.value) return;
      participants.value = data.participants ?? [];
    });
  }

  function detach() {
    if (unsubscribeRoster) {
      unsubscribeRoster();
      unsubscribeRoster = null;
    }
  }

  watch(
    () => [conn.socket.value, sessionId.value],
    () => {
      participants.value = [];
      attach();
    },
    { immediate: true },
  );

  onBeforeUnmount(detach);

  return { participants };
}
