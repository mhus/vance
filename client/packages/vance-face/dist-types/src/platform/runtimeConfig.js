/**
 * Runtime configuration loader. Fetches `./config.json` (relative
 * path so it works under any base path) at first call, caches the
 * result for the lifetime of the page.
 *
 * The file is committed in `vance-face/public/config.json` as a
 * dev/local default; the production docker entrypoint overwrites
 * it with env-injected values at pod start so the docker image
 * stays deployment-agnostic. See
 * `deployment/docker/face/docker-entrypoint.sh` and
 * `planning/vance-facelift-share-extension.md` for the wider
 * pattern.
 */
const FALLBACK = {
    product: 'vance',
    schema: 1,
    version: 'unknown',
    deployment: 'unknown',
    title: '',
    backlink: '',
};
let cached = null;
let inflight = null;
/**
 * Resolve the runtime config. Returns a cached value on subsequent
 * calls within the same page lifetime. Returns the fallback shape
 * when the fetch fails (e.g. dev server without docker, or the
 * static file was removed) so callers never have to null-check the
 * top-level shape — only individual optional fields.
 */
export async function loadRuntimeConfig() {
    if (cached !== null)
        return cached;
    if (inflight !== null)
        return inflight;
    inflight = (async () => {
        try {
            const res = await fetch('./config.json', { cache: 'no-store' });
            if (!res.ok)
                return FALLBACK;
            const parsed = (await res.json());
            if (parsed.product !== 'vance')
                return FALLBACK;
            return parsed;
        }
        catch {
            return FALLBACK;
        }
    })().then((c) => {
        cached = c;
        inflight = null;
        return c;
    });
    return inflight;
}
/** Synchronous access to the cached config — null until
 *  {@link loadRuntimeConfig} has resolved at least once. */
export function runtimeConfig() {
    return cached;
}
//# sourceMappingURL=runtimeConfig.js.map