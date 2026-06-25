/**
 * Host-side re-exports of the runner-registry types.
 *
 * The canonical type definitions live in {@code @vance/runner-registry}
 * so addons can depend on them without pulling in the full vance-face
 * bundle. This file re-exports them for the host's internal imports
 * that still use the relative {@code ./types} path.
 *
 * Host-internal additions (types the registry doesn't need) stay below.
 */
export type {
  RunState,
  RunAction,
  RunHandle,
  RunInput,
  RunAdapter,
  RunnerDocument,
} from '@vance/runner-registry';

import type { RunAdapter as IRunAdapter } from '@vance/runner-registry';

/**
 * Host-side runner list — built-in adapters that live in the host
 * bundle. Addons register themselves via {@code registerRunner()} from
 * {@code @vance/runner-registry}; the host calls {@code registerRunner}
 * for these built-ins at boot.
 */
export const builtInRunners: IRunAdapter[] = [];
