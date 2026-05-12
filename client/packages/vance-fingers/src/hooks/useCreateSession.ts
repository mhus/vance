import { useCallback, useState } from 'react';
import type { SessionCreateRequest, SessionCreateResponse } from '@vance/generated';
import { connectBrainWs } from '@/ws/connectBrainWs';

/**
 * One-shot helper for "+" → New Session in the chat picker. Opens a
 * WebSocket, sends `session-create` for the chosen project, closes,
 * and returns the new session id (or an error string for the UI).
 *
 * The chat-process is auto-bootstrapped server-side via
 * `SessionChatBootstrapper`; the response also reports the
 * `chatProcessId` / `chatProcessName` for completeness, but Mobile
 * doesn't store them — `useChatLive` re-resolves on entering the
 * Live screen.
 */
interface UseCreateSessionResult {
  creating: boolean;
  error: string | null;
  create: (projectId: string) => Promise<SessionCreateResponse | null>;
}

export function useCreateSession(): UseCreateSessionResult {
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const create = useCallback(
    async (projectId: string): Promise<SessionCreateResponse | null> => {
      setCreating(true);
      setError(null);
      let socket = null;
      try {
        socket = await connectBrainWs();
        const resp = await socket.send<SessionCreateRequest, SessionCreateResponse>(
          'session-create',
          { projectId },
        );
        return resp;
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Could not create session.');
        return null;
      } finally {
        if (socket) socket.close();
        setCreating(false);
      }
    },
    [],
  );

  return { creating, error, create };
}
