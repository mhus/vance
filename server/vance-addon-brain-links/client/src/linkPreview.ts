import { reactive } from 'vue';
import { fetchLinkPreview, safeUrl } from '@vance/shared';
import type { LinkPreviewDto } from '@vance/generated';

/**
 * What the page says about itself, for the fields the manifest does not
 * store.
 *
 * The backend is the brain's `GET /brain/{tenant}/link-preview` proxy — the
 * same one the chat's markdown link cards and the search app's hit pictures
 * use, cached in Mongo for a week and in the tab by `fetchLinkPreview`
 * itself. No second fetching path, and nothing here that the rest of the
 * product does not already do.
 *
 * A missing preview is a normal answer, not an error: `null` is stored and
 * the card falls back to the stored title and no picture. Storing the
 * negative answer matters — it is what stops a page without og:tags from
 * being asked about again on every re-render.
 */

const resolved = reactive<Record<string, LinkPreviewDto | null>>({});
const pending = new Set<string>();

/** The preview for `url`, or null while unknown or absent. */
export function previewFor(url: string): LinkPreviewDto | null {
  return resolved[url] ?? null;
}

/** True once an answer arrived, whether or not it contained anything. */
export function previewSettled(url: string): boolean {
  return url in resolved;
}

export function requestPreview(url: string): void {
  if (url in resolved || pending.has(url)) return;
  // Only http(s) — the proxy refuses anything else, and asking would spend
  // a round trip to be told so.
  if (!safeUrl(url)) return;
  pending.add(url);
  fetchLinkPreview(url)
    .then((dto) => {
      resolved[url] = dto.ok ? dto : null;
    })
    .catch(() => {
      // Best-effort by design: a link without a preview is still a link.
      resolved[url] = null;
    })
    .finally(() => pending.delete(url));
}

/**
 * Forget what we know about `url` so the next render asks again. Used by the
 * card's "refresh" action — the server-side cache holds a successful preview
 * for a week, and after fixing a page somebody wants to see it now.
 */
export function forgetPreview(url: string): void {
  delete resolved[url];
}

/**
 * The teaser to show: the reader's own text if there is one, otherwise the
 * page's description. Kept here rather than in the card so the card and any
 * future surface cannot disagree about which one wins.
 */
export function teaserOf(stored: string | null | undefined, url: string): string | null {
  if (stored && stored.trim().length > 0) return stored;
  const description = previewFor(url)?.description;
  return description && description.trim().length > 0 ? description : null;
}

/** True when the shown teaser is the reader's, not the page's. */
export function teaserIsOwn(stored: string | null | undefined): boolean {
  return !!stored && stored.trim().length > 0;
}

/** The picture to show, through `safeUrl` — og:image is foreign input. */
export function imageOf(stored: string | null | undefined, url: string): string | null {
  if (stored && stored.trim().length > 0) return safeUrl(stored);
  return safeUrl(previewFor(url)?.image);
}
