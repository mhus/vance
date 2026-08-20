import { safeUrl } from '@vance/shared';
import type { SearchHitView } from './generated/search/SearchHitView';

/**
 * Reading a hit for display.
 *
 * Everything here is pure and shared by the card and its opened detail, so the
 * two cannot drift into showing different things about the same result.
 */

export function thumbnail(hit: SearchHitView): string | null {
  const extras = hit.extras ?? {};
  const thumb = extras.thumbnailUrl ?? extras.coverThumbnailUrl ?? extras.imageUrl;
  return typeof thumb === 'string' ? safeUrl(thumb) : null;
}

/** The file for an image hit; `url` is the page it sits on, which is not the same. */
export function imageFile(hit: SearchHitView): string | null {
  const raw = (hit.extras ?? {}).imageUrl;
  return typeof raw === 'string' ? safeUrl(raw) : null;
}

/**
 * A remote URL as an `href`, or null when it must not become one.
 *
 * Every link on this surface goes through here. `url` and every `extras` entry
 * are written by the searched service, so a `javascript:` value would run on
 * this origin the moment somebody clicks a headline — and hiding the link is
 * better than a link that attacks the reader.
 */
export function link(raw: string | null | undefined): string | null {
  return safeUrl(raw);
}

/** The `extras` value at `key` if it is a linkable URL. */
export function extraLink(hit: SearchHitView, key: string): string | null {
  return safeUrl(extraText(hit, key));
}

export function extraText(hit: SearchHitView, key: string): string | null {
  const raw = (hit.extras ?? {})[key];
  return raw === undefined || raw === null ? null : String(raw);
}

/** Metadata worth a line under the title, per modality. */
export function metaLine(hit: SearchHitView): string {
  const bits: string[] = [];
  const push = (key: string, prefix = '') => {
    const v = extraText(hit, key);
    if (v) bits.push(prefix + v);
  };
  push('authors');
  push('author');
  push('venue');
  push('publicationYear');
  push('citedByCount', 'cited ');
  push('channel');
  push('duration');
  push('points', '▲ ');
  push('comments', '💬 ');
  push('date');
  push('publisher');
  push('firstPublishYear');
  if (hit.source) bits.unshift(hit.source);
  return bits.join(' · ');
}
