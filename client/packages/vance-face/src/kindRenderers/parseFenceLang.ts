/**
 * Fence-language parser for kind-tagged code blocks.
 *
 * Input: the `lang` part of a fenced code block, e.g. `mindmap` or
 * `mindmap theme=dark,direction=right`.
 * Output: `{ kind, meta }` where `kind` is the lowercased first
 * token and `meta` is the parsed `key=value` map of the optional
 * second segment.
 *
 * Spec: specification/inline-and-embedded-content.md §2 + §11.7.
 * v1 format is comma-separated `key=value`; whitespace inside
 * values is preserved as-is.
 */

export type FenceMeta = Record<string, string>;

export interface ParsedFence {
  kind: string;
  meta: FenceMeta;
}

export function parseFenceLang(lang: string | undefined | null): ParsedFence {
  const trimmed = (lang ?? '').trim();
  if (!trimmed) {
    return { kind: '', meta: {} };
  }
  const [head, ...rest] = trimmed.split(/\s+/);
  if (rest.length === 0) {
    return { kind: head.toLowerCase(), meta: {} };
  }
  const tail = rest.join(' ');
  const meta: FenceMeta = {};
  for (const part of tail.split(',')) {
    const eq = part.indexOf('=');
    if (eq < 0) {
      const k = part.trim();
      if (k) meta[k] = '';
      continue;
    }
    const k = part.slice(0, eq).trim();
    const v = part.slice(eq + 1).trim();
    if (k) meta[k] = v;
  }
  return { kind: head.toLowerCase(), meta };
}
