import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';

/**
 * Write side of the insights inspector: the engine-stop buttons. Kept out
 * of {@code useInsights.ts} on purpose — that one is the read-only inspector
 * API, this one is the control surface
 * ({@code planning/session-control.md} §2.2).
 *
 * Two stages per target: {@code stop} is the graceful engine stop (runs on
 * the process's lane, bounded wait), {@code forceStop} is the cut for wedged
 * processes (halt flag + immediate CLOSED, waits for nothing). Session
 * variants walk every non-closed process, chat-process included. All four
 * answer 204 — callers refresh their rows from the read endpoints.
 */
export interface UseInsightsControl {
  busy: Ref<boolean>;
  error: Ref<string | null>;
  stopProcess: (processId: string) => Promise<void>;
  forceStopProcess: (processId: string) => Promise<void>;
  stopSession: (sessionId: string) => Promise<void>;
  forceStopSession: (sessionId: string) => Promise<void>;
}

export function useInsightsControl(): UseInsightsControl {
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function call(path: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>('POST', path);
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
  };
}