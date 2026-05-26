import type { LinkPreviewDto } from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * In-memory LRU cache for link previews. Sized to ~50 entries which
 * covers a typical chat session without ballooning memory — older
 * entries get evicted on insert. Tab-scoped: when the user reloads,
 * the server-side Mongo cache absorbs the cold start.
 */
const CACHE_LIMIT = 50;
const cache = new Map<string, LinkPreviewDto>();

/**
 * In-flight de-duplication: when the same URL is requested twice
 * before the first response lands (e.g. the same link appears in
 * two messages currently on screen), share the promise so we only
 * hit the server once.
 */
const inflight = new Map<string, Promise<LinkPreviewDto>>();

/**
 * GET /brain/{tenant}/link-preview?url={url} — fetches OpenGraph
 * metadata for an external link through the Brain CORS proxy.
 *
 * Always resolves with a populated DTO; the caller checks `ok` to
 * decide between rendering a full card and a muted "preview not
 * available" badge. Network errors fall through as `RestError` —
 * the caller may swallow them silently since previews are best-effort.
 */
export async function fetchLinkPreview(url: string): Promise<LinkPreviewDto> {
  const cached = cache.get(url);
  if (cached) {
    // LRU touch: re-insert moves the entry to the end (Map preserves
    // insertion order, so this is the cheapest LRU implementation).
    cache.delete(url);
    cache.set(url, cached);
    return cached;
  }
  const existing = inflight.get(url);
  if (existing) return existing;

  const promise = brainFetch<LinkPreviewDto>(
    'GET',
    `link-preview?url=${encodeURIComponent(url)}`,
  )
    .then((dto) => {
      rememberPreview(url, dto);
      return dto;
    })
    .finally(() => {
      inflight.delete(url);
    });

  inflight.set(url, promise);
  return promise;
}

function rememberPreview(url: string, dto: LinkPreviewDto): void {
  if (cache.size >= CACHE_LIMIT) {
    // Map iteration order = insertion order; drop the oldest entry.
    const first = cache.keys().next();
    if (!first.done) cache.delete(first.value);
  }
  cache.set(url, dto);
}

/** Visible-for-testing: clears the in-memory cache. */
export function _clearLinkPreviewCache(): void {
  cache.clear();
  inflight.clear();
}
