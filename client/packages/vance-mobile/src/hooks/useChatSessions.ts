import { useEffect, useState } from 'react';
import {
  type SessionListRequest,
  type SessionListResponse,
  type SessionSummary,
} from '@vance/generated';
import { connectBrainWs } from '@/ws/connectBrainWs';

/**
 * One-shot session list for the chat picker. Opens a WebSocket,
 * sends `session-list`, closes. Sessions are listed only via WS in
 * the v1 protocol — there is no user-facing REST equivalent.
 *
 * The hook is intentionally fire-and-forget; the caller renders
 * `loading` / `error` once and pull-to-refresh re-runs the cycle.
 */
interface UseChatSessionsResult {
  sessions: SessionSummary[];
  loading: boolean;
  error: string | null;
  refresh: () => void;
}

export function useChatSessions(): UseChatSessionsResult {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    (async () => {
      let socket = null;
      try {
        socket = await connectBrainWs();
        const resp = await socket.send<SessionListRequest, SessionListResponse>(
          'session-list',
          {},
        );
        if (!cancelled) setSessions(resp.sessions ?? []);
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Could not load sessions.');
        }
      } finally {
        if (socket) socket.close();
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tick]);

  return {
    sessions,
    loading,
    error,
    refresh: () => setTick((t) => t + 1),
  };
}
