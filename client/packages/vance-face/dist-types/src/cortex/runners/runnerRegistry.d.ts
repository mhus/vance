import type { CortexDocument } from '../types';
import type { RunAdapter } from './types';
/**
 * Pick the first adapter willing to execute the given document, or
 * {@code null} when none matches. The shell uses this to decide
 * whether to show the Run-Button + log panel at all.
 */
export declare function resolveRunAdapter(doc: CortexDocument): RunAdapter | null;
//# sourceMappingURL=runnerRegistry.d.ts.map