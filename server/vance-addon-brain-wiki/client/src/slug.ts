/**
 * Client-side mirror of the Java {@code WikiFolderReader.slugify}
 * (`vance-addon-brain-wiki` server module). Kept byte-for-byte compatible so
 * the synchronous red-link check in {@code WorkPageEditor.resolveWikiLink}
 * matches what the server would generate for a page filename. Parity is
 * guarded by the case-table in `slug.test.ts` — change both (and the Java
 * side) together.
 *
 * Rule: lower-case; keep {@code a-z 0-9 _}; collapse any run of other
 * characters (incl. {@code - / . space}) to a single {@code -}; trim
 * trailing {@code -}.
 */
export function slugify(raw: string | null | undefined): string {
  if (raw == null) return '';
  let out = '';
  for (const ch of raw.toLowerCase()) {
    const c = ch.charCodeAt(0);
    const isWord =
      (c >= 97 && c <= 122) /* a-z */ ||
      (c >= 48 && c <= 57) /* 0-9 */ ||
      ch === '_';
    if (isWord) {
      out += ch;
    } else if (ch === '-' || ch === ' ' || ch === '/' || ch === '.') {
      if (out.length > 0 && out[out.length - 1] !== '-') out += '-';
    } else if (out.length > 0 && out[out.length - 1] !== '-') {
      out += '-';
    }
  }
  while (out.length > 0 && out[out.length - 1] === '-') {
    out = out.slice(0, -1);
  }
  return out;
}

/**
 * The name portion of a `[[Space/Name]]` wikilink target — everything
 * after the last `/`. A bare `[[Name]]` returns itself. Used for the
 * synchronous slug existence check (the server owns the space-aware
 * resolution; the client only needs the leaf slug for red-link styling).
 */
export function targetName(target: string): string {
  const slash = target.lastIndexOf('/');
  return slash < 0 ? target : target.slice(slash + 1);
}
