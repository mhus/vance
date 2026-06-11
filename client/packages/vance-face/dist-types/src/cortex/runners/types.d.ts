import type { Ref } from 'vue';
import type { CortexDocument } from '../types';
/**
 * Lifecycle state of one execution. Mirrors the backend's
 * {@code ScriptExecutionStatus} enum for the JS runner; future runners
 * (Python, Shell, R) must map their own state machine onto these
 * values so the shell UI stays runner-agnostic.
 */
export type RunState = 'idle' | 'starting' | 'running' | 'finished' | 'failed' | 'cancelled';
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
    /** Ask the backend to cancel. Safe to call after termination. */
    cancel(): Promise<void>;
    /** Release WS listeners / polling timers. Idempotent. */
    detach(): void;
}
export interface RunInput {
    doc: CortexDocument;
    projectId: string;
    /** Free-form args, runner-specific shape. Empty {@code {}} is fine. */
    args: Record<string, unknown>;
}
/**
 * Orthogonal capability: a runnable adapter declares it can execute
 * documents matching its predicate. Registered in
 * {@link ./runnerRegistry.ts}; the shell looks up at most one adapter
 * per tab (first-match wins).
 *
 * <p>Adapters are independent of the {@code docTypeRegistry} binding —
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
    matches(doc: CortexDocument): boolean;
    /**
     * Start an execution. The returned handle is reactive and live —
     * the shell template watches its refs. Throws on transport / auth
     * errors before the execution kicks off; once started, terminal
     * errors land on {@link RunHandle.error} instead.
     */
    execute(input: RunInput): Promise<RunHandle>;
}
//# sourceMappingURL=types.d.ts.map