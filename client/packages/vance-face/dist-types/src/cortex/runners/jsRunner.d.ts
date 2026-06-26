import type { RunAdapter } from '@vance/runner-registry';
/**
 * JS-execution adapter: drives the brain's {@code scripts/execute}
 * endpoint, subscribes to {@code script-execution-*} push events,
 * and falls back to short-interval polling when WS subscription
 * fails (e.g. the worker terminated before subscribe landed).
 *
 * <p>Drives the run inline below the editor (no modal). Same backend
 * endpoints the deleted Script-Cortex modal used — only the UI
 * shape changed.
 */
export declare const jsRunner: RunAdapter;
//# sourceMappingURL=jsRunner.d.ts.map