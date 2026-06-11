import type { CortexDocument } from '../types';
import type { RunAdapter } from './types';
import { jsRunner } from './jsRunner';

/**
 * Registered run adapters. First-match wins per
 * {@link resolveRunAdapter}; future language runners (Python, Shell, R)
 * register themselves here in their own modules.
 */
const adapters: RunAdapter[] = [jsRunner];

/**
 * Pick the first adapter willing to execute the given document, or
 * {@code null} when none matches. The shell uses this to decide
 * whether to show the Run-Button + log panel at all.
 */
export function resolveRunAdapter(doc: CortexDocument): RunAdapter | null {
  for (const a of adapters) {
    if (a.matches(doc)) return a;
  }
  return null;
}
