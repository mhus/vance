// Markdown → block list (TS counterpart of WorkPageParser.java).
// Kept line-based and grammar-precise rather than full-CommonMark — we
// only need to recognise the subset we serialise back out.

import yaml from 'js-yaml';
import type { Block, WorkPageDocument, ImageWidth, TodoItem } from './blocks';
import { IMAGE_WIDTHS } from './blocks';

const HEADING = /^(#{1,3})\s+(.+?)\s*$/;
const BULLET = /^\s*[-*+]\s+(.+?)\s*$/;
const NUMBERED = /^\s*\d+\.\s+(.+?)\s*$/;
const TODO = /^\s*[-*+]\s+\[([ xX])\]\s+(.+?)\s*$/;
const QUOTE = /^>\s?(.*)$/;
// Fence-open accepts 3+ backticks so containers (vance-columns) can use
// 4 backticks and still embed a regular ```code``` block inside.
const FENCE_OPEN = /^(`{3,})(\S*)\s*$/;
const DIVIDER = /^---+\s*$/;
const IMAGE_ONLY = /^!\[(.*?)\]\((.+?)\)\s*$/;
const TABLE_DIVIDER = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*$/;

/**
 * Parse a full workpage document into a typed {@link WorkPageDocument}.
 * Three input shapes are recognised:
 *
 * <ol>
 *   <li><b>Markdown with YAML front-matter</b> — {@code ---\n$meta:\n
 *       kind: workpage\n---\nBODY}. Standard workpage format.</li>
 *   <li><b>Pure YAML</b> — {@code $meta:\n  kind: workpage\ntitle: ...}.
 *       The {@code $meta.kind} header alone is enough for Vance to
 *       route the document to this editor, so we accept the format.
 *       No body blocks; only the headers are usable.</li>
 *   <li><b>Pure Markdown</b> — anything else. No headers; the entire
 *       text is the block body.</li>
 * </ol>
 */
export function parseDocument(fullMarkdown: string): WorkPageDocument {
  const empty: WorkPageDocument = {
    title: null,
    description: null,
    icon: null,
    cover: null,
    blocks: [],
  };
  if (!fullMarkdown) return empty;

  // 1. Markdown front-matter.
  if (fullMarkdown.startsWith('---\n')) {
    const end = fullMarkdown.indexOf('\n---\n', 4);
    if (end > 0) {
      const headers = extractHeaders(fullMarkdown.substring(4, end));
      const body = fullMarkdown.substring(end + 5);
      return { ...headers, blocks: parse(body) };
    }
  }

  // 2. Pure YAML — starts with a YAML key, no Markdown front-matter
  //    fence. Detect heuristically: the very first non-blank line is
  //    {@code <name>:} (a top-level YAML key) and the whole text parses
  //    as a YAML map with {@code $meta.kind === 'workpage'}.
  if (looksLikeYamlDoc(fullMarkdown)) {
    try {
      const loaded = yaml.load(fullMarkdown) as Record<string, unknown> | null;
      if (
        loaded &&
        typeof loaded === 'object' &&
        loaded.$meta &&
        typeof loaded.$meta === 'object' &&
        (loaded.$meta as { kind?: unknown }).kind === 'workpage'
      ) {
        return {
          title: typeof loaded.title === 'string' ? loaded.title : null,
          description:
            typeof loaded.description === 'string' ? loaded.description : null,
          icon: typeof loaded.icon === 'string' ? loaded.icon : null,
          cover: typeof loaded.cover === 'string' ? loaded.cover : null,
          blocks: [],
        };
      }
    } catch {
      /* fall through — treat as Markdown */
    }
  }

  // 3. Pure Markdown.
  return { ...empty, blocks: parse(fullMarkdown) };
}

function extractHeaders(headerText: string): {
  title: string | null;
  description: string | null;
  icon: string | null;
  cover: string | null;
} {
  try {
    const loaded = yaml.load(headerText) as Record<string, unknown> | null;
    if (loaded && typeof loaded === 'object') {
      return {
        title: typeof loaded.title === 'string' ? loaded.title : null,
        description:
          typeof loaded.description === 'string' ? loaded.description : null,
        icon: typeof loaded.icon === 'string' ? loaded.icon : null,
        cover: typeof loaded.cover === 'string' ? loaded.cover : null,
      };
    }
  } catch {
    /* fall through */
  }
  return { title: null, description: null, icon: null, cover: null };
}

function looksLikeYamlDoc(text: string): boolean {
  const firstNonBlank = text.split('\n').find((l) => l.trim().length > 0);
  if (!firstNonBlank) return false;
  // YAML top-level key: `name:` or `name: value`. Excludes Markdown
  // headings (`#`), lists (`-`/`*`), code-fences, etc.
  return /^[A-Za-z_$][\w$.-]*\s*:/.test(firstNonBlank);
}

/** Parse just the block body (no front-matter handling). */
export function parse(markdown: string): Block[] {
  if (!markdown) return [];
  const lines = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    if (line.trim() === '') {
      i++;
      continue;
    }

    // Fenced block.
    const mFence = FENCE_OPEN.exec(line);
    if (mFence) {
      const marker = mFence[1];
      const info = mFence[2];
      const end = findFenceClose(lines, i + 1, marker);
      const body = lines.slice(i + 1, end).join('\n');
      blocks.push(parseFence(info, body));
      i = end + 1;
      continue;
    }

    // Heading.
    const mH = HEADING.exec(line);
    if (mH) {
      blocks.push({ kind: 'heading', level: mH[1].length as 1 | 2 | 3, text: mH[2] });
      i++;
      continue;
    }

    // Divider.
    if (DIVIDER.test(line)) {
      blocks.push({ kind: 'divider' });
      i++;
      continue;
    }

    // Image-only line. Width preset (small/medium/large/full) may be
    // suffixed to the alt-text after a pipe — strip it off here.
    const mImg = IMAGE_ONLY.exec(line);
    if (mImg) {
      const { alt, width } = parseImageAlt(mImg[1]);
      const img: Block = width
        ? { kind: 'image', alt, src: mImg[2], width }
        : { kind: 'image', alt, src: mImg[2] };
      blocks.push(img);
      i++;
      continue;
    }

    // Todo before bullet (todo also matches bullet pattern).
    if (TODO.test(line)) {
      const items: TodoItem[] = [];
      while (i < lines.length) {
        const m = TODO.exec(lines[i]);
        if (!m) break;
        items.push({ checked: m[1] !== ' ', text: m[2] });
        i++;
      }
      blocks.push({ kind: 'todo', items });
      continue;
    }

    if (BULLET.test(line) && !TODO.test(line)) {
      const items: string[] = [];
      while (i < lines.length) {
        const m = BULLET.exec(lines[i]);
        if (!m || TODO.test(lines[i])) break;
        items.push(m[1]);
        i++;
      }
      blocks.push({ kind: 'bullet-list', items });
      continue;
    }

    if (NUMBERED.test(line)) {
      const items: string[] = [];
      while (i < lines.length) {
        const m = NUMBERED.exec(lines[i]);
        if (!m) break;
        items.push(m[1]);
        i++;
      }
      blocks.push({ kind: 'numbered-list', items });
      continue;
    }

    if (QUOTE.test(line)) {
      const quoteLines: string[] = [];
      while (i < lines.length) {
        const m = QUOTE.exec(lines[i]);
        if (!m) break;
        quoteLines.push(m[1]);
        i++;
      }
      blocks.push({ kind: 'quote', text: quoteLines.join('\n') });
      continue;
    }

    // Table: pipe-bearing line followed by divider.
    if (line.includes('|') && i + 1 < lines.length && TABLE_DIVIDER.test(lines[i + 1])) {
      const headers = splitTableRow(line);
      const rows: string[][] = [];
      let j = i + 2;
      while (j < lines.length) {
        const l = lines[j];
        if (l.trim() === '' || !l.includes('|')) break;
        rows.push(splitTableRow(l));
        j++;
      }
      blocks.push({ kind: 'table', headers, rows });
      i = j;
      continue;
    }

    // Paragraph — collect consecutive lines until a block-start.
    const paraLines: string[] = [];
    while (i < lines.length) {
      const l = lines[i];
      if (l.trim() === '' || isBlockStart(l, i + 1 < lines.length ? lines[i + 1] : '')) {
        break;
      }
      paraLines.push(l);
      i++;
    }
    if (paraLines.length > 0) {
      blocks.push({ kind: 'paragraph', text: paraLines.join('\n') });
    }
  }

  return blocks;
}

function findFenceClose(lines: string[], from: number, marker: string): number {
  // CommonMark-style: closing fence must be a run of the same character
  // with at least the same length as the opener.
  const re = new RegExp(`^${marker[0]}{${marker.length},}\\s*$`);
  for (let j = from; j < lines.length; j++) {
    if (re.test(lines[j])) return j;
  }
  return lines.length;
}

function isBlockStart(line: string, next: string): boolean {
  if (HEADING.test(line)) return true;
  if (BULLET.test(line)) return true;
  if (NUMBERED.test(line)) return true;
  if (TODO.test(line)) return true;
  if (QUOTE.test(line)) return true;
  if (FENCE_OPEN.test(line)) return true;
  if (DIVIDER.test(line)) return true;
  if (IMAGE_ONLY.test(line)) return true;
  if (line.includes('|') && TABLE_DIVIDER.test(next)) return true;
  return false;
}

function parseFence(info: string, body: string): Block {
  if (!info || !info.startsWith('vance-')) {
    return { kind: 'code', lang: info || null, code: body };
  }
  // Markdown-body kinds — handle BEFORE the YAML parse attempt below.
  // Their bodies legitimately contain nested fences (`vance-columns`
  // carrying a `vance-embed`, code blocks, …) which would make
  // yaml.load() throw and incorrectly fall through to unknown-fence.
  if (info === 'vance-toc') {
    return { kind: 'toc' };
  }
  // Compose cells carry a raw YAML manifest as their body — kept verbatim,
  // not key/value-parsed (the body is itself the compose YAML).
  if (info === 'vance-compose') {
    return { kind: 'compose', yaml: body };
  }
  if (info === 'vance-columns') {
    // Column separator uses an HTML-comment marker so it survives
    // Markdown rendering elsewhere and is extremely unlikely to be
    // typed by accident inside a column. Optional width follows the
    // word: `<!--vance:column 0.4-->`.
    const widthSep = /\n<!--vance:column(?:\s+([\d.]+))?-->\n/g;
    const parts: string[] = [];
    const widths: (number | null)[] = [null];
    let last = 0;
    let m: RegExpExecArray | null;
    while ((m = widthSep.exec(body)) !== null) {
      parts.push(body.substring(last, m.index));
      const w = m[1] ? Number(m[1]) : NaN;
      widths.push(Number.isFinite(w) && w > 0 ? w : null);
      last = m.index + m[0].length;
    }
    parts.push(body.substring(last));
    const cols = parts.map((p, i) => ({
      blocks: parse(p.trim()),
      width: widths[i] ?? null,
    }));
    return { kind: 'columns', columns: cols };
  }
  // YAML-body kinds — parsed key/value-style.
  let parsed: Record<string, unknown> = {};
  try {
    const loaded = yaml.load(body);
    if (loaded && typeof loaded === 'object') {
      parsed = loaded as Record<string, unknown>;
    }
  } catch {
    return { kind: 'unknown-fence', info, body };
  }
  switch (info) {
    case 'vance-callout':
      return {
        kind: 'callout',
        severity: str(parsed, 'severity') ?? 'info',
        title: str(parsed, 'title'),
        body: str(parsed, 'body') ?? '',
      };
    case 'vance-toggle':
      return {
        kind: 'toggle',
        summary: str(parsed, 'summary') ?? '',
        body: str(parsed, 'body') ?? '',
      };
    case 'vance-dataview':
      return { kind: 'dataview', source: str(parsed, 'source') ?? '' };
    case 'vance-link':
      return {
        kind: 'link-card',
        href: str(parsed, 'href') ?? '',
        title: str(parsed, 'title'),
        description: str(parsed, 'description'),
      };
    case 'vance-embed':
      return { kind: 'embed', uri: str(parsed, 'uri') ?? '' };
    case 'vance-form':
      return {
        kind: 'form',
        data: str(parsed, 'data') ?? '',
        saveScript: str(parsed, 'saveScript') ?? '',
        session: parsed.session === true,
        form: (parsed.form && typeof parsed.form === 'object' && !Array.isArray(parsed.form))
          ? (parsed.form as Record<string, unknown>)
          : { single: false, fields: [] },
      };
    case 'vance-input':
      return {
        kind: 'input',
        data: str(parsed, 'data') ?? '',
        multiline: parsed.multiline === true,
        saveScript: str(parsed, 'saveScript') ?? '',
        session: parsed.session === true,
      };
    case 'vance-button':
      return {
        kind: 'button',
        buttonType: str(parsed, 'type') ?? 'script',
        script: str(parsed, 'script') ?? '',
        title: str(parsed, 'title') ?? '',
      };
    default:
      return { kind: 'unknown-fence', info, body };
  }
}

function parseImageAlt(raw: string): { alt: string; width: ImageWidth | null } {
  const pipe = raw.lastIndexOf('|');
  if (pipe < 0) return { alt: raw, width: null };
  const suffix = raw.substring(pipe + 1).trim().toLowerCase();
  if (!(IMAGE_WIDTHS as readonly string[]).includes(suffix)) {
    return { alt: raw, width: null };
  }
  return { alt: raw.substring(0, pipe).trim(), width: suffix as ImageWidth };
}

function splitTableRow(line: string): string[] {
  let trimmed = line.trim();
  if (trimmed.startsWith('|')) trimmed = trimmed.substring(1);
  if (trimmed.endsWith('|')) trimmed = trimmed.substring(0, trimmed.length - 1);
  return trimmed.split('|').map((s) => s.trim());
}

function str(obj: Record<string, unknown>, key: string): string | null {
  const v = obj[key];
  if (v == null) return null;
  return String(v);
}
