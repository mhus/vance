import type { Ref } from 'vue';

/**
 * Minimal document shape that {@link RunAdapter.matches} and
 * {@link RunInput.doc} operate on. The host's full
 * {@code CortexDocument} structurally satisfies this interface, so
 * runners in the host bundle can pass their richer type without
 * casts. Addons that don't want a host dependency write against
 * this minimal shape directly.
 */
export interface RunnerDocument {
  id: string;
  path: string;
  mimeType?: string | null;
  kind?: string | null;
}

/**
 * Lifecycle state of one execution. Mirrors the backend's
 * {@code ScriptExecutionStatus} enum for the JS runner; future runners
 * (Python, Shell, R, TeX) must map their own state machine onto these
 * values so the shell UI stays runner-agnostic.
 */
export type RunState =
  | 'idle'
  | 'starting'
  | 'running'
  | 'finished'
  | 'failed'
  | 'cancelled';

/**
 * Optional post-run action rendered as a button in the log panel.
 * Runners that produce an artefact (e.g. TeX → PDF) expose an action
 * to open / download it, keeping the shell free of runner-specific
 * knowledge.
 */
export interface RunAction {
  /** Stable identifier for keying. */
  readonly id: string;
  /** Short button label, e.g. {@code "Open PDF"}. */
  readonly label: string;
  /** Optional emoji / icon glyph. */
  readonly icon?: string;
  /** Called when the user clicks the action button. */
  execute(): Promise<void>;
}

/**
 * Reactive handle returned by {@link RunAdapter.execute}. The shell
 * binds template state directly to these refs — no extra service layer
 * between runner and UI.
 *
 * <p>The handle owns its WebSocket / polling lifecycle. Call
 * {@link detach} when the tab unmounts or the user starts a new run
 * so listeners don't leak. {@link cancel} asks the backend to abort
 * the in-flight execution; it is a no-op when the execution already
 * terminated.
 */
export interface RunHandle {
  /** Backend-assigned execution id — opaque to the shell. */
  readonly id: string;
  readonly state: Ref<RunState>;
  /** Streaming log lines from stdout / stderr, in order. */
  readonly logLines: Ref<string[]>;
  /** Set when the execution finishes normally. */
  readonly result: Ref<unknown>;
  /** Human-readable error message when state is {@code 'failed'}. */
  readonly error: Ref<string | null>;
  /** Wall-clock duration in milliseconds, populated on terminal state. */
  readonly durationMs: Ref<number | null>;
  /** Post-run action buttons (e.g. "Open PDF"). Empty when no actions. */
  readonly actions?: Ref<RunAction[]>;
  /** Ask the backend to cancel. Safe to call after termination. */
  cancel(): Promise<void>;
  /** Release WS listeners / polling timers. Idempotent. */
  detach(): void;
}

export interface RunInput {
  doc: RunnerDocument;
  projectId: string;
  /** Free-form args, runner-specific shape. Empty {@code {}} is fine. */
  args: Record<string, unknown>;
}

/**
 * Orthogonal capability: a runnable adapter declares it can execute
 * documents matching its predicate. Registered in the global
 * runner-registry; the shell looks up at most one adapter per tab
 * (first-match wins).
 *
 * <p>Adapters are independent of the doc-type binding —
 * a {@code kind: list} document is not runnable, a plain
 * {@code text/javascript} document is. Both go through the same
 * DocumentTabShell which composes view-binding with run-adapter.
 */
export interface RunAdapter {
  /** Stable identifier for debug logs + future addon dispatch. */
  readonly id: string;
  /** Short label for the Run button, e.g. {@code "Run JS"}. */
  readonly label: string;
  /** Returns {@code true} when this adapter can execute the document. */
  matches(doc: RunnerDocument): boolean;
  /**
   * Start an execution. The returned handle is reactive and live —
   * the shell template watches its refs. Throws on transport / auth
   * errors before the execution kicks off; once started, terminal
   * errors land on {@link RunHandle.error} instead.
   */
  execute(input: RunInput): Promise<RunHandle>;
}

declare global {
  // eslint-disable-next-line no-var
  var __VANCE_RUNNER_REGISTRY__: Map<string, RunAdapter> | undefined;
}

function store(): Map<string, RunAdapter> {
  let s = globalThis.__VANCE_RUNNER_REGISTRY__;
  if (!s) {
    s = new Map<string, RunAdapter>();
    globalThis.__VANCE_RUNNER_REGISTRY__ = s;
  }
  return s;
}

/**
 * Register a Run adapter. Idempotent — registering the same id again
 * replaces the previous entry (handy for HMR + addon re-load).
 */
export function registerRunner(adapter: RunAdapter): void {
  store().set(adapter.id, adapter);
}

/**
 * Resolve a Run adapter by its stable id.
 */
export function resolveRunner(id: string): RunAdapter | undefined {
  return store().get(id);
}

/**
 * First adapter whose matcher accepts the given document.
 * Iteration order is insertion order — host built-ins register before
 * addons, so a built-in wins ties.
 */
export function resolveRunAdapter(
  doc: RunnerDocument,
): RunAdapter | undefined {
  for (const adapter of store().values()) {
    if (adapter.matches(doc)) return adapter;
  }
  return undefined;
}

/**
 * Snapshot of all currently-registered runners in insertion order.
 */
export function listRunners(): RunAdapter[] {
  return [...store().values()];
}
