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

export function useSessionRoster(sessionId: Ref<string | null>) {
  const participants = ref<SessionParticipantDto[]>([]);
  const conn = useWsConnection();

  let unsubscribeRoster: (() => void) | null = null;
  let baselineEstablished = false;
  const changeListeners = new Set<(change: RosterChange) => void>();
  const initialListeners = new Set<(participants: SessionParticipantDto[]) => void>();

  function diff(
    prev: SessionParticipantDto[],
    next: SessionParticipantDto[],
  ): RosterChange | null {
    const prevIds = new Set(prev.map((p) => p.editorId));
    const nextIds = new Set(next.map((p) => p.editorId));
    const joined = next.filter((p) => !prevIds.has(p.editorId));
    const left = prev.filter((p) => !nextIds.has(p.editorId));
    if (joined.length === 0 && left.length === 0) return null;
    return { joined, left, at: new Date() };
  }

  function attach() {
    detach();
    const socket = conn.socket.value;
    const sid = sessionId.value;
    if (!socket) return;
    unsubscribeRoster = socket.on<SessionRosterData>('session-roster', (data) => {
      if (!data || data.sessionId !== sessionId.value) return;
      const next = data.participants ?? [];
      const isBaseline = !baselineEstablished;
      const change = isBaseline ? null : diff(participants.value, next);
      participants.value = next;
      baselineEstablished = true;
      if (isBaseline) {
        for (const listener of initialListeners) {
          try {
            listener(next);
          } catch (err) {
            console.warn('[useSessionRoster] initial listener threw', err);
          }
        }
      }
      if (change) {
        for (const listener of changeListeners) {
          try {
            listener(change);
          } catch (err) {
            console.warn('[useSessionRoster] change listener threw', err);
          }
        }
      }
    });
    // The server pushes a session-roster frame on join, but the
    // listener above only attaches once ChatView is mounted — by
    // then the server's initial push has already gone out and we
    // missed the baseline. Fetch it actively here so the composable
    // always knows the current roster, race-free.
    if (!sid) return;
    socket.send<unknown, SessionRosterData>('session-who', {})
      .then((reply) => {
        if (!reply) return;
        if (reply.sessionId !== sid && reply.sessionId !== sessionId.value) {
          return;
        }
        if (baselineEstablished) return; // a push already landed first
        const next = reply.participants ?? [];
        participants.value = next;
        baselineEstablished = true;
        for (const listener of initialListeners) {
          try {
            listener(next);
          } catch (err) {
            console.warn('[useSessionRoster] initial listener threw', err);
          }
        }
      })
      .catch((err) => {
        console.warn('[useSessionRoster] session-who failed', err);
      });
  }

  function detach() {
    if (unsubscribeRoster) {
      unsubscribeRoster();
      unsubscribeRoster = null;
    }
  }

  /**
   * Subscribe to per-delta roster changes. Returns an unsubscribe
   * function. The first frame after attach is treated as a fresh
   * baseline (initialise participants without emitting "joined" for
   * everyone already present) because {@code participants} starts as
   * an empty array — every diff between {@code []} and the server's
   * snapshot would otherwise spam "X joined" on session-load.
   */
  function onChange(handler: (change: RosterChange) => void): () => void {
    changeListeners.add(handler);
    return () => changeListeners.delete(handler);
  }

  /**
   * Subscribe to the initial roster baseline — fired exactly once
   * per attach, when the active session-who lookup resolves (or the
   * first server push lands, whichever arrives first). Lets a chat
   * view render a "currently here:" line right after the user enters
   * a shared session, without needing to type {@code /who} manually.
   */
  function onInitial(handler: (participants: SessionParticipantDto[]) => void): () => void {
    initialListeners.add(handler);
    return () => initialListeners.delete(handler);
  }

  watch(
    () => [conn.socket.value, sessionId.value],
    () => {
      participants.value = [];
      baselineEstablished = false;
      attach();
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    detach();
    changeListeners.clear();
    initialListeners.clear();
  });

  return { participants, onChange, onInitial };
}
