import { jsRunner } from './jsRunner';
/**
 * Registered run adapters. First-match wins per
 * {@link resolveRunAdapter}; future language runners (Python, Shell, R)
 * register themselves here in their own modules.
 */
const adapters = [jsRunner];
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