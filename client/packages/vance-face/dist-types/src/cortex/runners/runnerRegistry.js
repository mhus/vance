import { jsRunner } from './jsRunner';
import { pythonRunner } from './pythonRunner';
import { texRunner } from './texRunner';
/**
 * Registered run adapters. First-match wins per
 * {@link resolveRunAdapter}; future language runners (Shell, R)
 * register themselves here in their own modules.
 */
const adapters = [jsRunner, pythonRunner, texRunner];
/**
 * Pick the first adapter willing to execute the given document, or
 * {@code null} when none matches. The shell uses this to decide
 * whether to show the Run-Button + log panel at all.
 */
export function resolveRunAdapter(doc) {
    for (const a of adapters) {
        if (a.matches(doc))
            return a;
    }
    return null;
}
//# sourceMappingURL=runnerRegistry.js.map