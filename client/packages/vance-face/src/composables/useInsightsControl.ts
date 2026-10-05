import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';

/**
 * Write side of the insights inspector: the engine-stop buttons and the
 * session-sharing toggle. Kept out of {@code useInsights.ts} on purpose —
 * that one is the read-only inspector API, this one is the control surface
 * ({@code planning/session-control.md} §2.2).
 *
 * Two stages per target: {@code stop} is the graceful engine stop (runs on
 * the process's lane, bounded wait), {@code forceStop} is the cut for wedged
 * processes (halt flag + immediate CLOSED, waits for nothing). Session
 * variants walk every non-closed process, chat-process included. Every call
 * answers 204 — callers refresh their rows from the read endpoints. The
 * sharing toggle flips {@code allowMultipleClients} ("shared": every
 * connection in the tenant may join —
 * {@code specification/multi-user-sessions.md} §1).
 */
export interface UseInsightsControl {
  busy: Ref<boolean>;
  error: Ref<string | null>;
  stopProcess: (processId: string) => Promise<void>;
  forceStopProcess: (processId: string) => Promise<void>;
  stopSession: (sessionId: string) => Promise<void>;
  forceStopSession: (sessionId: string) => Promise<void>;
  setSharing: (sessionId: string, shared: boolean) => Promise<void>;
}

export function useInsightsControl(): UseInsightsControl {
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function call(path: string, body?: unknown): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>('POST', path, body === undefined ? {} : { body });
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      busy.value = false;
    }
  }

  return {
    busy,
    error,
    stopProcess: (processId: string) =>
      call(`admin/processes/${encodeURIComponent(processId)}/stop`),
    forceStopProcess: (processId: string) =>
      call(`admin/processes/${encodeURIComponent(processId)}/force-stop`),
    stopSession: (sessionId: string) =>
      call(`admin/sessions/${encodeURIComponent(sessionId)}/stop`),
    forceStopSession: (sessionId: string) =>
      call(`admin/sessions/${encodeURIComponent(sessionId)}/force-stop`),
    setSharing: (sessionId: string, shared: boolean) =>
      call(`admin/sessions/${encodeURIComponent(sessionId)}/sharing`, { shared }),
  };
}