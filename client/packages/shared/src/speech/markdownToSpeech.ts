/**
 * Strip Markdown formatting for text-to-speech rendering — TS port of
 * {@code de.mhus.vance.shared.voice.MarkdownToSpeech}.
 *
 * Spec: specification/inline-and-embedded-content.md §10.3.
 *
 * Voice clients (mobile-voice, foot-with-TTS, future web-voice) call
 * this on the engine's raw Markdown before handing it to the system
 * or cloud TTS synthesiser. Engine output stays uniform; channel-
 * specific rendering is the client's responsibility.
 *
 * Pure string transformation — no DOM, no storage, no platform
 * dependency. Safe from any client.
 */

const ORDINALS_DE = [
  'Erstens', 'Zweitens', 'Drittens', 'Viertens', 'Fünftens',
  'Sechstens', 'Siebtens', 'Achtens', 'Neuntens', 'Zehntens',
];
const NUMBERS_DE = [
  'Eins', 'Zwei', 'Drei', 'Vier', 'Fünf',
  'Sechs', 'Sieben', 'Acht', 'Neun', 'Zehn',
];

// Same patterns as Java side; flags differ slightly for JS regex.
const FENCED       = /^( {0,3})(```+|~~~+)([^\n]*)\n([\s\S]*?)\n\1\2[^\n]*$/gm;
const TABLE        = /^\|.+\|[ \t]*\n[ \t]*\|[\s|:\-]+\|[ \t]*\n(?:[ \t]*\|.+\|[ \t]*\n?)*/gm;
const IMAGE_LINK   = /!\[([^\]]*)\]\(([^)]*)\)/g;
const LINK         = /\[([^\]]*)\]\(([^)]*)\)/g;
const HEADING      = /^ {0,3}#{1,6}\s+(.*?)\s*#*\s*$/gm;
const BULLET_ITEM  = /^ {0,3}[*+\-]\s+(.*)$/;
const ORDERED_ITEM = /^ {0,3}\d+[.)]\s+(.*)$/;
const HRULE        = /^ {0,3}([-*_])(?:\s*\1){2,}\s*$/gm;
const INLINE_CODE  = /`([^`]+)`/g;
const BOLD_ITALIC  = /([*_]{1,3})(\S(?:.*?\S)?)\1/g;
const STRIKE       = /~~([^~]+)~~/g;
const HTML_TAG     = /<[^>]+>/g;
const FOOTNOTE_REF = /\[\^[^\]]+\]/g;
const BLOCKQUOTE   = /^\s*>\s?/gm;

export function markdownToSpeech(markdown: string | null | undefined): string {
  if (!markdown) return '';
  let s = markdown;

  // ── 1. Fenced code blocks → "(Code-Block mit N Zeilen)"
  s = s.replace(FENCED, (_match, _indent, _fence, _lang, body: string) => {
    const lines = body.length === 0 ? 0 : body.split('\n').length;
    return `(Code-Block mit ${lines} Zeilen)`;
  });

  // ── 2. Pipe-tables → "(Tabelle mit X Zeilen, Y Spalten)"
  s = s.replace(TABLE, (block: string) => {
    const lines = block.split('\n');
    let rows = 0;
    let cols = 0;
    for (let i = 0; i < lines.length; i++) {
      const ln = lines[i].trim();
      if (!ln) continue;
      if (i === 1 && /^\|[\s|:\-]+\|$/.test(ln)) continue;
      rows++;
      if (cols === 0) {
        const inner = ln.replace(/^\||\|$/g, '');
        cols = inner.split('|').length;
      }
    }
    return `(Tabelle mit ${rows} Zeilen, ${cols} Spalten)`;
  });

  // ── 3. Image links → alt; regular links → text (or "Link zu <host>")
  s = s.replace(IMAGE_LINK, (_m, alt: string) => alt.trim() || 'Bild');
  s = s.replace(LINK, (_m, text: string, url: string) => {
    const t = text.trim();
    if (t) return t;
    const host = extractHost(url.trim());
    return host ? `Link zu ${host}` : 'Link';
  });

  // ── 4. Headings → "text." (period pause hint)
  s = s.replace(HEADING, (_m, text: string) => {
    const t = text.trim();
    if (!t) return '';
    return /[.!?]$/.test(t) ? t : t + '.';
  });

  // ── 5. Horizontal rules → ". ."
  s = s.replace(HRULE, '. .');

  // ── 6. Lists → "Erstens: …; Zweitens: …"
  s = collapseList(s, BULLET_ITEM, ORDINALS_DE);
  s = collapseList(s, ORDERED_ITEM, NUMBERS_DE);

  // ── 7. Blockquote marker
  s = s.replace(BLOCKQUOTE, '');

  // ── 8. Inline markers
  s = s.replace(INLINE_CODE, '$1');
  s = s.replace(STRIKE, '$1');
  s = s.replace(BOLD_ITALIC, '$2');

  // ── 9. HTML tags + footnote refs
  s = s.replace(HTML_TAG, '');
  s = s.replace(FOOTNOTE_REF, '');

  // ── 10. Collapse leftover whitespace
  s = s.replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
  return s;
}

function extractHost(url: string): string {
  if (!url) return '';
  try {
    const schemeEnd = url.indexOf('://');
    let rest = schemeEnd >= 0 ? url.substring(schemeEnd + 3) : url;
    const slash = rest.indexOf('/');
    if (slash >= 0) rest = rest.substring(0, slash);
    const question = rest.indexOf('?');
    if (question >= 0) rest = rest.substring(0, question);
    return rest;
  } catch {
    return '';
  }
}

function collapseList(input: string, itemPattern: RegExp, connectors: string[]): string {
  const lines = input.split('\n');
  const out: string[] = [];
  let bucket: string[] = [];
  const flush = (): void => {
    if (bucket.length === 0) return;
    out.push(joinList(bucket, connectors));
    bucket = [];
  };
  for (const line of lines) {
    const m = itemPattern.exec(line);
    if (m) {
      bucket.push(m[1].trim());
    } else {
      flush();
      out.push(line);
    }
  }
  flush();
  return out.join('\n');
}

function joinList(items: string[], connectors: string[]): string {
  return items
    .map((it, i) => `${i < connectors.length ? connectors[i] : 'Weitens'}: ${it}`)
    .join('; ');
}
