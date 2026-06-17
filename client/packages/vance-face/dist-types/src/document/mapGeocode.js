// Lazy geocoding helper for `kind: map` documents. Resolves
// `place`-only locations to coordinates via the brain endpoint
// `GET /brain/{tenant}/geocode?q=...`, then memoises the result in a
// per-page cache so repeat renders of the same map don't refetch.
//
// The endpoint returns 404 for empty / unresolvable queries; the
// helper returns `null` in that case and the renderer skips the
// affected entry with a warning surfaced in the side panel.
import { brainFetch } from '@vance/shared';
const cache = new Map();
export async function geocodePlace(query) {
    const key = query.trim().toLowerCase();
    if (!key)
        return null;
    const cached = cache.get(key);
    if (cached)
        return cached;
    const pending = (async () => {
        try {
            const url = `geocode?q=${encodeURIComponent(query)}`;
            return await brainFetch('GET', url);
        }
        catch (e) {
            // 404 == unresolved; treat any failure as "skip this point".
            // The renderer's responsibility is to keep going for the rest
            // of the map rather than blank everything because of one bad
            // place name.
            console.warn('geocodePlace: failed to resolve', query, e);
            return null;
        }
    })();
    cache.set(key, pending);
    return pending;
}
//# sourceMappingURL=mapGeocode.js.map