import { computed, ref } from 'vue';
import type { ComposeOutputView, ComposeRunResult } from '../extensions/VanceCompose';
import {
  readComposeOutputs,
  writeComposeOutputs,
  readComposeRun,
  writeComposeRun,
  clearComposeManaged,
} from '../extensions/composeOutputs';

const POLL_MS = 3000;

/** The host-provided compose REST surface (run/poll/cancel). */
export interface ComposeHost {
  runCompose: (yaml: string) => Promise<ComposeRunResult>;
  pollCompose: (runId: string) => Promise<ComposeRunResult>;
  cancelCompose: (runId: string) => Promise<ComposeRunResult>;
}

export interface UseComposeRunOptions {
  /** Current manifest YAML (reactive getter — usually `() => node.attrs.yaml`). */
  yaml: () => string;
  /** Persist the manifest YAML (usually `updateAttributes({ yaml })`). */
  setYaml: (yaml: string) => void;
  /** Host REST surface. */
  host: ComposeHost;
}

/**
 * The shared single-run engine behind every compose-family block (plain
 * `vance-compose` and the `vance-compose-*` script blocks): the two-phase run
 * (start REST → optional poll), stop/cancel, the `$run:`/`$output:` managed
 * blocks, live progress + resume-after-refresh. Cross-block features (Run All
 * Until / Clear All Output) stay in the owning NodeView — they are not part of
 * a single block's run.
 */
export function useComposeRun(opts: UseComposeRunOptions) {
  const running = ref(false);
  const error = ref<string | null>(null);
  const result = ref<ComposeRunResult | null>(null);
  const progress = ref<ComposeRunResult | null>(null);
  /** Server runId once phase 2 (polling) is reached — enables Stop. */
  const runId = ref<string | null>(null);
  const cancelling = ref(false);

  let polling = false;
  let timer: ReturnType<typeof setTimeout> | undefined;

  /** Outputs recorded by a prior successful run (`$output:`). */
  const persisted = computed<ComposeOutputView[]>(() => readComposeOutputs(opts.yaml()));
  /** Stop is only meaningful in phase 2 (a runId exists to cancel). */
  const canStop = computed(() => running.value && runId.value != null);
  const runGlyph = computed(() => (!running.value ? '▶' : runId.value ? '■' : '…'));

  function stopTimer() {
    polling = false;
    if (timer) clearTimeout(timer);
    timer = undefined;
  }

  function finishWith(res: ComposeRunResult) {
    stopTimer();
    running.value = false;
    cancelling.value = false;
    runId.value = null;
    progress.value = null;
    result.value = res;
    const outputs = (res.tasks ?? []).flatMap((t) =>
      (t.outputs ?? []).map((o) => ({ path: o.path, uri: o.uri, kind: o.kind, title: o.title })),
    );
    opts.setYaml(res.success ? writeComposeOutputs(opts.yaml(), outputs) : clearComposeManaged(opts.yaml()));
  }

  function startPolling(id: string) {
    polling = true;
    const tick = async () => {
      if (!polling) return;
      try {
        const res = await opts.host.pollCompose(id);
        if (res.running) {
          progress.value = res;
          timer = setTimeout(tick, POLL_MS);
        } else {
          finishWith(res);
        }
      } catch {
        stopTimer();
        running.value = false;
        progress.value = null;
        error.value = 'Lauf nicht mehr verfügbar (Pod-Neustart?) — bitte neu ausführen.';
        opts.setYaml(clearComposeManaged(opts.yaml()));
      }
    };
    timer = setTimeout(tick, POLL_MS);
  }

  async function run() {
    if (running.value) return;
    stopTimer();
    running.value = true;
    error.value = null;
    result.value = null;
    progress.value = null;
    try {
      const res = await opts.host.runCompose(opts.yaml());
      if (res.running && res.runId) {
        runId.value = res.runId;
        progress.value = res;
        opts.setYaml(writeComposeRun(opts.yaml(), { id: res.runId, startedAt: new Date().toISOString() }));
        startPolling(res.runId);
      } else {
        finishWith(res);
      }
    } catch (e) {
      running.value = false;
      error.value = e instanceof Error ? e.message : 'Compose run failed';
    }
  }

  /** ■ Stop (phase 2): cancel the in-flight run on the server. */
  async function stop() {
    const rid = runId.value;
    if (rid) {
      cancelling.value = true;
      try {
        const res = await opts.host.cancelCompose(rid);
        if (!res.running) finishWith(res); // else the poll loop observes it shortly
      } catch {
        // Best-effort — the poll loop still winds things down.
      } finally {
        cancelling.value = false;
      }
      return;
    }
    // No server run yet (phase 1) — detach and drop the $run marker.
    stopTimer();
    running.value = false;
    progress.value = null;
    opts.setYaml(clearComposeManaged(opts.yaml()));
  }

  function onRunButton() {
    if (!running.value) run();
    else if (canStop.value) stop();
  }

  /** Drop this block's outputs (managed block) and reset state. */
  function clearOutput() {
    stopTimer();
    running.value = false;
    progress.value = null;
    result.value = null;
    error.value = null;
    runId.value = null;
    opts.setYaml(clearComposeManaged(opts.yaml()));
  }

  /** Resume polling a run that was in flight before a reload (`$run:`). */
  function resumeIfInFlight() {
    const marker = readComposeRun(opts.yaml());
    if (marker) {
      running.value = true;
      runId.value = marker.id;
      startPolling(marker.id);
    }
  }

  return {
    running,
    error,
    result,
    progress,
    runId,
    cancelling,
    persisted,
    canStop,
    runGlyph,
    run,
    stop,
    onRunButton,
    clearOutput,
    resumeIfInFlight,
    teardown: stopTimer,
  };
}
