import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ChatRole,
  ProgressKind,
  type ChatMessageAppendedData,
  type ChatMessageChunkData,
  type ChatMessageDto,
  type ProcessProgressNotification,
  type ProcessSteerRequest,
  type ProcessSteerResponse,
  type SessionListRequest,
  type SessionListResponse,
  type SessionResumeRequest,
  type SessionResumeResponse,
} from '@vance/generated';
import { type BrainWsApi } from '@vance/shared';
import { connectBrainWs } from '@/ws/connectBrainWs';
import { listChatHistory } from '@/api/chatHistoryApi';
import { speakAloud, stopSpeaking } from '@/voice/speakAloud';
import { normalizeEnum } from '@/util/enum';

/**
 * Live state for the {@link ChatLiveScreen}: WebSocket lifecycle,
 * history snapshot, streaming-chunk buffer, and an outbound `send`.
 *
 * Lifecycle:
 *  1. Mount with `sessionId` → load REST history → open WS →
 *     `session-resume` → subscribe to chat events.
 *  2. Streaming chunks accumulate in {@link streamingDrafts}; the
 *     committing `chat-message-appended` flushes the matching draft
 *     and pushes a final {@link ChatMessageDto}.
 *  3. Outbound: optimistic `tmp_*` local echo, then `process-steer`.
 *     The canonical `chat-message-appended` for the same content
 *     replaces the optimistic entry on arrival.
 *  4. Close on unmount; no auto-reconnect — the user navigates away
 *     deliberately.
 *  5. If the WS dies before the user navigates away, an exponential
 *     backoff (1, 2, 4, 8 s, capped at 30) attempts to reopen and
 *     re-resume the session. The `connectionState` exposes the
 *     status to the UI.
 *
 * The chat-process-name is fixed to `'chat'` per the
 * `SessionChatBootstrapper` convention; `session-bootstrap`'s
 * response would also report it, but `session-resume` doesn't.
 */

export type ChatConnectionState = 'connecting' | 'open' | 'reconnecting' | 'closed';

const CHAT_PROCESS_NAME = 'chat';
const OPTIMISTIC_PREFIX = 'tmp_';

interface UseChatLiveResult {
  messages: ChatMessageDto[];
  /** Per-thinkProcessId in-flight stream draft. */
  streamingDrafts: Map<string, { processName: string; role: ChatRole; chunk: string }>;
  connectionState: ChatConnectionState;
  sessionDisplay: string;
  /**
   * Most recent process-progress hint, e.g. "thinking…", "32 in / 14
   * out · 2 calls". Auto-clears ~1.2 s after the last frame so the
   * chat doesn't accumulate ghost banners.
   */
  progressHint: string | null;
  send: (text: string) => Promise<void>;
}

/** How long a single progress hint stays on screen before fading out. */
const PROGRESS_HINT_TTL_MS = 1200;

function formatProgress(p: ProcessProgressNotification): string | null {
  const kind = normalizeEnum(ProgressKind, p.kind);
  if (kind === ProgressKind.STATUS && p.status?.text) {
    return p.status.text;
  }
  if (kind === ProgressKind.METRICS && p.metrics) {
    const m = p.metrics;
    return `${m.tokensInTotal} in / ${m.tokensOutTotal} out · ${m.llmCallCount} calls`;
  }
  if (kind === ProgressKind.PLAN) {
    return 'planning…';
  }
  return null;
}

export function useChatLive(sessionId: string): UseChatLiveResult {
  const [messages, setMessages] = useState<ChatMessageDto[]>([]);
  const [streamingDrafts, setStreamingDrafts] = useState<
    Map<string, { processName: string; role: ChatRole; chunk: string }>
  >(new Map());
  const [connectionState, setConnectionState] = useState<ChatConnectionState>('connecting');
  const [sessionDisplay, setSessionDisplay] = useState(sessionId);
  const [progressHint, setProgressHint] = useState<string | null>(null);

  const socketRef = useRef<BrainWsApi | null>(null);
  const liveRef = useRef(true);
  const reconnectAttemptRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const progressTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const open = useCallback(async () => {
    if (!liveRef.current) return;
    setConnectionState((s) => (s === 'closed' ? s : s));
    try {
      const socket = await connectBrainWs();
      if (!liveRef.current) {
        socket.close();
        return;
      }
      socketRef.current = socket;
      reconnectAttemptRef.current = 0;
      setConnectionState('open');

      // Resume the session and pull a friendly display name from the
      // session-list response. Both calls are best-effort — failure
      // surfaces as the raw session id in the title.
      try {
        await socket.send<SessionResumeRequest, SessionResumeResponse>('session-resume', {
          sessionId,
        });
      } catch (e) {
        // The session-resume reply might be an error if the session
        // is bound elsewhere. Surface to console for diagnostics; the
        // UI remains usable for receive-only.
        console.warn('session-resume failed', e);
      }
      try {
        const listResp = await socket.send<SessionListRequest, SessionListResponse>(
          'session-list',
          {},
        );
        const summary = listResp.sessions?.find((s) => s.sessionId === sessionId);
        if (summary?.displayName) setSessionDisplay(summary.displayName);
      } catch {
        // Fall back to the raw id.
      }

      // Subscribe to live chat events. Each unsubscribe is captured
      // in `unsubs` and run when the socket closes.
      const unsubs: Array<() => void> = [];
      unsubs.push(
        socket.on<ChatMessageAppendedData>('chat-message-appended', (data) => {
          const role = normalizeEnum(ChatRole, data.role);
          // Drop the matching streaming draft — its content is now
          // the committed final message.
          setStreamingDrafts((prev) => {
            if (!prev.has(data.thinkProcessId)) return prev;
            const next = new Map(prev);
            next.delete(data.thinkProcessId);
            return next;
          });
          setMessages((prev) => {
            // Dedupe optimistic local echoes by role + content.
            let dropIdx = -1;
            if (role === ChatRole.USER) {
              dropIdx = prev.findIndex(
                (m) =>
                  m.messageId.startsWith(OPTIMISTIC_PREFIX) &&
                  m.role === ChatRole.USER &&
                  m.content === data.content,
              );
            }
            // Dedupe against history that already has this id.
            const existsIdx = prev.findIndex((m) => m.messageId === data.chatMessageId);
            if (existsIdx >= 0) return prev;

            const final: ChatMessageDto = {
              messageId: data.chatMessageId,
              thinkProcessId: data.thinkProcessId,
              role,
              content: data.content,
              createdAt: data.createdAt,
            };
            if (dropIdx >= 0) {
              const copy = prev.slice();
              copy.splice(dropIdx, 1, final);
              return copy;
            }
            return [...prev, final];
          });
          if (role === ChatRole.ASSISTANT) {
            speakAloud(data.content);
          }
        }),
      );
      unsubs.push(
        socket.on<ChatMessageChunkData>('chat-message-stream-chunk', (data) => {
          const role = normalizeEnum(ChatRole, data.role);
          setStreamingDrafts((prev) => {
            const next = new Map(prev);
            const existing = next.get(data.thinkProcessId);
            next.set(data.thinkProcessId, {
              processName: data.processName,
              role,
              chunk: (existing?.chunk ?? '') + data.chunk,
            });
            return next;
          });
        }),
      );
      unsubs.push(
        socket.on<ProcessProgressNotification>('process-progress', (data) => {
          const text = formatProgress(data);
          if (text === null) return;
          setProgressHint(text);
          if (progressTimerRef.current !== null) {
            clearTimeout(progressTimerRef.current);
          }
          progressTimerRef.current = setTimeout(() => {
            setProgressHint(null);
            progressTimerRef.current = null;
          }, PROGRESS_HINT_TTL_MS);
        }),
      );

      const closeUnsub = socket.onClose(() => {
        for (const off of unsubs) off();
        socketRef.current = null;
        if (!liveRef.current) {
          setConnectionState('closed');
          return;
        }
        // Schedule a reconnect with exponential backoff.
        setConnectionState('reconnecting');
        const attempt = ++reconnectAttemptRef.current;
        const delayMs = Math.min(30_000, 1_000 * Math.pow(2, attempt - 1));
        reconnectTimerRef.current = setTimeout(() => {
          void open();
        }, delayMs);
      });
      unsubs.push(closeUnsub);
    } catch (e) {
      console.warn('chat WS connect failed', e);
      if (!liveRef.current) return;
      setConnectionState('reconnecting');
      const attempt = ++reconnectAttemptRef.current;
      const delayMs = Math.min(30_000, 1_000 * Math.pow(2, attempt - 1));
      reconnectTimerRef.current = setTimeout(() => {
        void open();
      }, delayMs);
    }
  }, [sessionId]);

  // Boot: load history, then open WS.
  useEffect(() => {
    liveRef.current = true;
    let cancelled = false;
    (async () => {
      try {
        const history = await listChatHistory(sessionId);
        if (!cancelled) setMessages(history);
      } catch (e) {
        console.warn('failed to load chat history', e);
      }
      if (!cancelled) await open();
    })();

    return () => {
      cancelled = true;
      liveRef.current = false;
      if (reconnectTimerRef.current !== null) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      if (progressTimerRef.current !== null) {
        clearTimeout(progressTimerRef.current);
        progressTimerRef.current = null;
      }
      const socket = socketRef.current;
      socketRef.current = null;
      if (socket) socket.close();
      stopSpeaking();
      setConnectionState('closed');
    };
  }, [sessionId, open]);

  const send = useCallback(async (text: string) => {
    const socket = socketRef.current;
    if (!socket) {
      console.warn('cannot send: socket not open');
      return;
    }
    // Optimistic local echo.
    const tmpId = `${OPTIMISTIC_PREFIX}${Date.now()}`;
    setMessages((prev) => [
      ...prev,
      {
        messageId: tmpId,
        thinkProcessId: '',
        role: ChatRole.USER,
        content: text,
      },
    ]);
    try {
      await socket.send<ProcessSteerRequest, ProcessSteerResponse>('process-steer', {
        processName: CHAT_PROCESS_NAME,
        content: text,
      });
    } catch (e) {
      // Roll the optimistic message back on hard failure so the user
      // is not misled into believing the message was delivered.
      setMessages((prev) => prev.filter((m) => m.messageId !== tmpId));
      console.warn('process-steer failed', e);
    }
  }, []);

  return {
    messages,
    streamingDrafts,
    connectionState,
    sessionDisplay,
    progressHint,
    send,
  };
}
