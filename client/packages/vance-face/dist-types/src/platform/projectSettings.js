// Lazy loader for project-scoped cascade settings — calls
// `GET /brain/{tenant}/settings/cascade?projectId=X&key=K1&key=K2`
// and memoises the result per (projectId, key-set) pair for the
// lifetime of the page.
//
// Use cases: infrastructure-style config that depends on the
// currently-active project (map tile URL, image-style defaults, …).
// Keys must appear on the backend's `PUBLIC_CASCADE_KEYS` allowlist;
// anything else returns 400.
//
// Why not the cookie? The data cookie is minted at login and
// cannot follow project switches inside one session. Anything
// that varies with the active project belongs here.
import { brainFetch } from '@vance/shared';
const cache = new Map();
function keyFor(projectId, keys) {
    // Sort the key list so callers that pass the same keys in
    // different orders share the same cache slot.
    return `${projectId}::${[...keys].sort().join(',')}`;
}
export async function loadProjectCascadeSettings(projectId, keys) {
    if (keys.length === 0)
        return {};
    const key = keyFor(projectId, keys);
    const cached = cache.get(key);
    if (cached)
        return cached;
    const params = new URLSearchParams();
    if (projectId)
        params.set('projectId', projectId);
    for (const k of keys)
        params.append('key', k);
    const pending = (async () => {
        try {
            return await brainFetch('GET', `settings/cascade?${params.toString()}`);
        }
        catch (e) {
            console.warn('loadProjectCascadeSettings failed', { projectId, keys, e });
            return {};
        }
    })();
    cache.set(key, pending);
    return pending;
}
//# sourceMappingURL=projectSettings.js.map