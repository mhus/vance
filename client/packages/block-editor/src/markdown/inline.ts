// Inline-mark parser + serializer. Block.text strings store Markdown
// inline syntax (`**bold**`, `*italic*`, `` `code` ``, `[text](url)`,
// `~~strike~~`); this module converts to/from ProseMirror inline JSON
// (one `text` node per mark-region with `marks: [...]`).
//
// Algorithm is a single-pass left-to-right scanner: at each cursor we
// try the recognised patterns in priority order (link > bold > strike
// > italic > code). Order matters because `**` would otherwise match
// two adjacent `*` italic markers. Plain runs between marks become
// bare `{type:'text', text:...}` nodes.
//
// Round-trip is byte-stable for the cases this parser handles: parse →
// serialize returns the original string (modulo whitespace inside
// nested marks — which the user can't currently express anyway).

import type { JSONContent } from '@tiptap/core';

export interface InlineMark {
  type: 'bold' | 'italic' | 'code' | 'strike' | 'link';
  attrs?: Record<string, unknown>;
}

export interface InlineSegment {
  text: string;
  marks: InlineMark[];
}

/** Markdown inline string → ProseMirror inline-node JSON array. */
export function parseInlineToProseMirror(text: string): JSONContent[] {
  const segments = parseInline(text);
  return segments.map((seg) => {
    const node: JSONContent = { type: 'text', text: seg.text };
    if (seg.marks.length > 0) node.marks = seg.marks as JSONContent['marks'];
    return node;
  });
}

/**
 * ProseMirror inline-node JSON array → Markdown inline string.
 * Marks are wrapped outside→in (link → strike → bold → italic → code)
 * so the result re-parses to the same node list. Unknown marks are
 * silently dropped.
 */
export function serializeProseMirrorInline(nodes: JSONContent[] | undefined): string {
  if (!nodes) return '';
  return nodes
    .map((n) => {
      if (n.type !== 'text' || typeof n.text !== 'string') return '';
      let s = n.text;
      const marks = (n.marks ?? []) as Array<{ type?: string; attrs?: Record<string, unknown> }>;
      const has = (type: string) => marks.find((m) => m?.type === type);
      // Apply in fixed order so the output is deterministic. Innermost
      // wraps closest to the text (code), outermost wraps furthest out
      // (link/strike). Tiptap stores marks per-node so order in the
      // input array doesn't matter for us.
      if (has('code')) s = '`' + s + '`';
      if (has('italic')) s = '*' + s + '*';
      if (has('bold')) s = '**' + s + '**';
      if (has('strike')) s = '~~' + s + '~~';
      const link = has('link');
      if (link && link.attrs && typeof link.attrs.href === 'string') {
        s = '[' + s + '](' + (link.attrs.href as string) + ')';
      }
      return s;
    })
    .join('');
}

/** Markdown inline string → typed segment list (testable, no Tiptap). */
export function parseInline(text: string): InlineSegment[] {
  if (!text) return [];
  const segments: InlineSegment[] = [];
  let plain = '';

  function flushPlain() {
    if (plain.length > 0) {
      segments.push({ text: plain, marks: [] });
      plain = '';
    }
  }

  function pushMarked(s: string, marks: InlineMark[]) {
    flushPlain();
    if (s.length === 0) return;
    segments.push({ text: s, marks });
  }

  let i = 0;
  while (i < text.length) {
    // Escaped char — emit literally, skip the backslash.
    if (text[i] === '\\' && i + 1 < text.length) {
      plain += text[i + 1];
      i += 2;
      continue;
    }

    // 1. Link [text](url) — try first because `[` can otherwise be
    //    plain.
    if (text[i] === '[') {
      const close = findMatching(text, i, '[', ']');
      if (close > i && text[close + 1] === '(') {
        const parenClose = findMatching(text, close + 1, '(', ')');
        if (parenClose > close + 1) {
          const linkText = text.substring(i + 1, close);
          const href = text.substring(close + 2, parenClose);
          pushMarked(linkText, [{ type: 'link', attrs: { href } }]);
          i = parenClose + 1;
          continue;
        }
      }
    }

    // 2. Bold ** … ** (before italic because `*` would otherwise match)
    if (text[i] === '*' && text[i + 1] === '*') {
      const closeIdx = text.indexOf('**', i + 2);
      if (closeIdx > i + 2) {
        pushMarked(text.substring(i + 2, closeIdx), [{ type: 'bold' }]);
        i = closeIdx + 2;
        continue;
      }
    }

    // 3. Strike ~~ … ~~
    if (text[i] === '~' && text[i + 1] === '~') {
      const closeIdx = text.indexOf('~~', i + 2);
      if (closeIdx > i + 2) {
        pushMarked(text.substring(i + 2, closeIdx), [{ type: 'strike' }]);
        i = closeIdx + 2;
        continue;
      }
    }

    // 4. Italic * … * (single asterisk, not part of **)
    if (text[i] === '*' && text[i + 1] !== '*' && (i === 0 || text[i - 1] !== '*')) {
      let close = -1;
      for (let j = i + 1; j < text.length; j++) {
        if (text[j] === '*' && text[j + 1] !== '*' && text[j - 1] !== '*') {
          close = j;
          break;
        }
      }
      if (close > i + 1) {
        pushMarked(text.substring(i + 1, close), [{ type: 'italic' }]);
        i = close + 1;
        continue;
      }
    }

    // 5. Inline code ` … `
    if (text[i] === '`') {
      const closeIdx = text.indexOf('`', i + 1);
      if (closeIdx > i) {
        pushMarked(text.substring(i + 1, closeIdx), [{ type: 'code' }]);
        i = closeIdx + 1;
        continue;
      }
    }

    plain += text[i];
    i++;
  }
  flushPlain();
  return segments;
}

/** Inverse of {@link parseInline} — useful for testing. */
export function serializeInline(segments: InlineSegment[]): string {
  return segments
    .map((seg) => {
      let s = seg.text;
      const has = (t: InlineMark['type']) => seg.marks.find((m) => m.type === t);
      if (has('code')) s = '`' + s + '`';
      if (has('italic')) s = '*' + s + '*';
      if (has('bold')) s = '**' + s + '**';
      if (has('strike')) s = '~~' + s + '~~';
      const link = has('link');
      if (link && link.attrs && typeof link.attrs.href === 'string') {
        s = '[' + s + '](' + (link.attrs.href as string) + ')';
      }
      return s;
    })
    .join('');
}

function findMatching(text: string, openIdx: number, open: string, close: string): number {
  let depth = 1;
  for (let i = openIdx + 1; i < text.length; i++) {
    if (text[i] === '\\') {
      i++;
      continue;
    }
    if (text[i] === open) depth++;
    else if (text[i] === close) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}
