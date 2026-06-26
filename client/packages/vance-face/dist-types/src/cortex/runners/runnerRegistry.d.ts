/**
 * Host-side runner registry facade.
 *
 * Built-in adapters (JS, Python, TeX) are registered at boot via
 * {@code registerRunner()} from {@code @vance/runner-registry}. Addons
 * register their own runners from their {@code ./register} federation
 * expose. The host and addons share the same globalThis-backed Map.
 *
 * This module re-exports the registry functions for the host's
 * internal use and registers the built-in runners at import time.
 */
export { registerRunner, resolveRunner, resolveRunAdapter, listRunners, } from '@vance/runner-registry';
export type { RunAdapter, RunHandle, RunInput, RunState, RunAction, RunnerDocument, } from '@vance/runner-registry';
//# sourceMappingURL=runnerRegistry.d.ts.map