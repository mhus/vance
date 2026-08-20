import { reactive } from 'vue';
import { fetchLinkPreview, safeUrl } from '@vance/shared';

/**
 * Preview pictures for hits whose provider shipped none.
 *
 * Serper's news index carries a lead image and its image search carries the
 * file itself; organic web results carry nothing. For those this asks the
 * brain's link-preview proxy — the same `GET /brain/{tenant}/link-preview`
 * that the chat's markdown link cards use, cached in Mongo for a week and in
 * the tab by `fetchLinkPreview` itself. No second fetching path, and no
 * fetching that the rest of the product does not already do.
 *
 * A missing preview is a normal answer, not an error: `null` is stored and
 * the caller renders nothing. Storing it matters — it is what stops a hit
 * that has no og:image from being asked about again on every re-render.
 */
const resolved = reactive<Record<string, string | null>>({});
const pending = new Set<string>();

/** The resolved picture for `url`, or null while unknown or absent. */
export function previewImageFor(url: string): string | null {
  return resolved[url] ?? null;
}

/** True once an answer arrived, whether or not it contained a picture. */
export function previewSettled(url: string): boolean {
  return url in resolved;
}

export function requestPreviewImage(url: string): void {
  if (url in resolved || pending.has(url)) return;
  // Only http(s) — the proxy refuses anything else, and asking would spend a
  // round trip to be told so.
  if (!safeUrl(url)) return;
  pending.add(url);
  fetchLinkPreview(url)
    .then((dto) => {
      // Through safeUrl(): og:image is written by the page we just read.
      resolved[url] = dto.ok ? safeUrl(dto.image) : null;
    })
    .catch(() => {
      // Best-effort by design: a link without a picture still links.
      resolved[url] = null;
    })
    .finally(() => pending.delete(url));
}
