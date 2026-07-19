/**
 * Host-side runner registry facade.
 *
 * Built-in adapters (JS, Python) are registered at boot via
 * {@code registerRunner()} from {@code @vance/runner-registry}. Addons
 * register their own runners from their {@code ./register} federation
 * expose. The host and addons share the same globalThis-backed Map.
 *
 * This module re-exports the registry functions for the host's
 * internal use and registers the built-in runners at import time.
 */
export {
  registerRunner,
  resolveRunner,
  resolveRunAdapter,
  listRunners,
} from '@vance/runner-registry';
export type {
  RunAdapter,
  RunHandle,
  RunInput,
  RunState,
  RunAction,
  RunnerDocument,
} from '@vance/runner-registry';

// ── Built-in runner registration ────────────────────────────────
//
// Import and register the host-bundled runners. When a runner moves
// to an addon, its import + register call moves to the addon's
// register.ts and this file shrinks by one entry — the shell and
// all other runners stay unchanged.

import { registerRunner } from '@vance/runner-registry';
import { jsRunner } from './jsRunner';
import { pythonRunner } from './pythonRunner';

registerRunner(jsRunner);
registerRunner(pythonRunner);
