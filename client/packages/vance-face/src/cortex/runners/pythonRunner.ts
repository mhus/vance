import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  PythonExecuteResponse,
  PythonExecutionStatus,
} from '@vance/generated';
import type {
  RunAdapter,
  RunHandle,
  RunInput,
  RunState,
  RunnerDocument,
} from '@vance/runner-registry';

const PY_MIMES = new Set([
  'text/x-python',
  'application/x-python',
  'text/python',
]);
const PY_EXTS = ['.py'];

function isPythonDocument(doc: RunnerDocument): boolean {
  const m = (doc.mimeType ?? '').toLowerCase().trim();
  if (m && PY_MIMES.has(m)) return true;
  const p = doc.path.toLowerCase();
  return PY_EXTS.some((ext) => p.endsWith(ext));
}

/**
 * Python-execution adapter for the Cortex Run button. Talks to the
 * brain's REST surface ({@code POST /python/execute},
 * {@code GET /python/executions/{id}},
 * {@code POST /python/executions/{id}/cancel}) and surfaces a live
 * snapshot through polling — the ExecManager pipeline this runs on
 * doesn't emit WS push events (unlike ScriptCortex's JS path), so
 * the runner just re-fetches the status every ~1.5s and replaces the
 * log buffer with the latest stdout / stderr snapshot.
 *
 * <p>Args translate to shell-args (List&lt;String&gt;), unlike the JS
 * adapter's free-form arg-map. The user types a JSON-array string in
 * the toolbar (parsed by DocumentTabShell.onRun); we accept the array
 * here verbatim.
 */
export const pythonRunner: RunAdapter = {
  id: 'py',
  label: 'Run Python',
  matches: isPythonDocument,
  async execute({ doc, projectId, args }: RunInput): Promise<RunHandle> {
    const state: Ref<RunState> = ref('starting');
    const logLines: Ref<string[]> = ref([]);
    const result: Ref<unknown> = ref(null);
    const error: Ref<string | null> = ref(null);
    const durationMs: Ref<number | null> = ref(null);

    let executionId: string | null = null;
    let pollTimer: number | null = null;
    let detached = false;

    // The DocumentTabShell args-input is a JSON object; for Python we
    // expect an array. Convert object-shaped args to a flat list of
    // 'key=value' strings as a pragmatic default — power users can
    // still pass a top-level array via a future arg-input variant.
    function toShellArgs(): string[] {
      if (Array.isArray(args)) return args.map(String);
      const out: string[] = [];
      for (const [k, v] of Object.entries(args)) {
        out.push(`${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`);
      }
      return out;
    }

    function stopPolling(): void {
      if (pollTimer != null) {
        window.clearTimeout(pollTimer);
        pollTimer = null;
      }
    }

    function applySnapshot(snap: PythonExecutionStatus): void {
      state.value = snap.state as RunState;
      durationMs.value = snap.durationMs ?? null;
      error.value = snap.errorMessage ?? null;
      // Concatenate stdout + stderr into one ordered log buffer. We
      // tag stderr lines so the UI can colour them if it ever wants;
      // the simple log-pane currently shows them inline.
      const lines: string[] = [];
      const stdout = snap.stdout ?? '';
      const stderr = snap.stderr ?? '';
      if (stdout) {
        for (const line of stdout.split('\n')) {
          if (line.length > 0) lines.push(`[stdout] ${line}`);
        }
      }
      if (stderr) {
        for (const line of stderr.split('\n')) {
          if (line.length > 0) lines.push(`[stderr] ${line}`);
        }
      }
      logLines.value = lines;
      if (state.value === 'finished' && snap.exitCode != null) {
        result.value = { exitCode: snap.exitCode };
      }
    }

    function startPolling(): void {
      stopPolling();
      const tick = async (): Promise<void> => {
        if (detached || !executionId) return;
        try {
          const snap = await brainFetch<PythonExecutionStatus>(
            'GET',
            `python/executions/${encodeURIComponent(executionId)}`
            + `?projectId=${encodeURIComponent(projectId)}`,
          );
          applySnapshot(snap);
          if (snap.state !== 'running' && snap.state !== 'starting') {
            stopPolling();
            return;
          }
        } catch (e) {
          console.warn('[cortex/py-runner] poll error:', e);
          // 404 means the job evicted — stop polling but keep last
          // known state visible.
          stopPolling();
          return;
        }
        pollTimer = window.setTimeout(tick, 1500);
      };
      // Slightly faster first poll catches very short scripts before
      // the 1.5s steady cadence kicks in.
      pollTimer = window.setTimeout(tick, 400);
    }

    // Kick off the execution. POST failure means "couldn't even
    // start"; record as terminal failure so the shell renders a banner.
    let resp: PythonExecuteResponse;
    try {
      resp = await brainFetch<PythonExecuteResponse>(
        'POST',
        `python/execute?projectId=${encodeURIComponent(projectId)}`,
        {
          body: {
            scriptId: doc.id,
            args: toShellArgs(),
            sourceName: doc.path,
          },
        },
      );
    } catch (e) {
      state.value = 'failed';
      error.value = e instanceof Error ? e.message : 'Execute failed';
      return makeHandle();
    }

    executionId = resp.executionId;
    state.value = 'running';
    startPolling();

    function makeHandle(): RunHandle {
      return {
        get id(): string { return executionId ?? ''; },
        state,
        logLines,
        result,
        error,
        durationMs,
        async cancel(): Promise<void> {
          if (!executionId) return;
          try {
            await brainFetch<void>(
              'POST',
              `python/executions/${encodeURIComponent(executionId)}/cancel`
              + `?projectId=${encodeURIComponent(projectId)}`,
            );
          } catch (e) {
            error.value = e instanceof Error ? e.message : 'Cancel failed';
          }
        },
        detach(): void {
          if (detached) return;
          detached = true;
          stopPolling();
        },
      };
    }

    return makeHandle();
  },
};
