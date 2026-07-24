// Block list → Markdown (TS counterpart of WorkPageSerializer.java).

import yaml from 'js-yaml';
import type { Block, WorkPageDocument } from './blocks';
import { findBlockByFence } from '../blockRegistry';
import { bodyFromAttrs } from './customBlock';

/**
 * The document front-matter (everything serializeDocument emits before the
 * body). Split out so callers can measure its length — the body char offsets
 * a selection maps to (see {@link serializeWithBlockRanges}) are relative to
 * the FULL document, i.e. shifted by this header.
 */
export function documentHeader(doc: WorkPageDocument): string {
  // Dump the whole front-matter through js-yaml rather than hand-rolling it:
  // a value that merely *begins* with a YAML indicator (@, `, !, *, &, |, >,
  // %, [, {, or '- '/'? ') is not caught by a naive contains-check and would be
  // emitted unquoted → invalid YAML → yaml.load throws on reload → title/etc.
  // silently lost. yaml.dump quotes exactly what needs quoting (matches
  // renderFence). Key insertion order is preserved by js-yaml.
  const header: Record<string, unknown> = { $meta: { kind: 'workpage' } };
  if (doc.title && doc.title.trim().length > 0) header.title = doc.title;
  if (doc.description && doc.description.trim().length > 0) header.description = doc.description;
  if (doc.icon && doc.icon.trim().length > 0) header.icon = doc.icon;
  if (doc.cover && doc.cover.trim().length > 0) header.cover = doc.cover;
  const dumped = yaml.dump(header, {
    lineWidth: -1,
    noCompatMode: true,
    quotingType: '"',
    forceQuotes: false,
  });
  return '---\n' + dumped + '---\n';
}

/**
 * Render a full workpage document (front-matter + body).
 */
export function serializeDocument(doc: WorkPageDocument): string {
  return documentHeader(doc) + serialize(doc.blocks);
}

/** Render a block list (no front-matter). */
export function serialize(blocks: Block[]): string {
  const parts: string[] = [];
  for (let i = 0; i < blocks.length; i++) {
    if (i > 0) parts.push('');
    parts.push(renderBlock(blocks[i]).trimEnd());
  }
  return parts.join('\n') + '\n';
}

/**
 * Like {@link serialize}, but also returns each block's `{start,end}` char
 * range in the produced markdown. Mirrors {@link serialize}'s join exactly
 * (blocks separated by a blank line, trailing newline) — used to map an
 * editor selection to a body char range. `md` is byte-identical to
 * `serialize(blocks)`.
 */
export function serializeWithBlockRanges(
  blocks: Block[],
): { md: string; ranges: Array<{ start: number; end: number }> } {
  const ranges: Array<{ start: number; end: number }> = [];
  let md = '';
  for (let i = 0; i < blocks.length; i++) {
    if (i > 0) md += '\n\n';
    const start = md.length;
    md += renderBlock(blocks[i]).trimEnd();
    ranges.push({ start, end: md.length });
  }
  md += '\n';
  return { md, ranges };
}

function renderBlock(b: Block): string {
  switch (b.kind) {
    case 'paragraph':
      return b.text + '\n';
    case 'heading':
      return '#'.repeat(b.level) + ' ' + b.text + '\n';
    case 'bullet-list':
      return b.items.map((i) => `- ${i}`).join('\n') + '\n';
    case 'numbered-list':
      return b.items.map((i, idx) => `${idx + 1}. ${i}`).join('\n') + '\n';
    case 'todo':
      return (
        b.items.map((i) => `- [${i.checked ? 'x' : ' '}] ${i.text}`).join('\n') + '\n'
      );
    case 'quote':
      return (
        b.text
          .split('\n')
          .map((l) => '> ' + l)
          .join('\n') + '\n'
      );
    case 'code': {
      const body = b.code + (b.code.endsWith('\n') ? '' : '\n');
      const f = fenceFor(body);
      return f + (b.lang ?? '') + '\n' + body + f + '\n';
    }
    case 'divider':
      return '---\n';
    case 'image': {
      // Width preset goes into the alt-text as a pipe-suffix so the
      // markdown round-trips losslessly. Default 'full' (or null) is
      // omitted to keep the common case clean.
      const altWithWidth = b.width && b.width !== 'full'
        ? `${b.alt}|${b.width}`
        : b.alt;
      return `![${altWithWidth}](${b.src})\n`;
    }
    case 'table': {
      // Escape so a cell containing '|' doesn't spawn extra columns on
      // re-parse, and a multi-line cell survives as one line. Order matters:
      // backslash first (so the backslash we add for '|' isn't re-escaped),
      // then '|', then newline → <br>. splitTableRow reverses this.
      const esc = (s: string) =>
        s.replace(/\\/g, '\\\\').replace(/\|/g, '\\|').replace(/\r?\n/g, '<br>');
      const head = '| ' + b.headers.map(esc).join(' | ') + ' |';
      const div = '| ' + b.headers.map(() => '---').join(' | ') + ' |';
      const rows = b.rows.map((r) => '| ' + r.map(esc).join(' | ') + ' |');
      return [head, div, ...rows].join('\n') + '\n';
    }
    case 'toggle':
      return renderFence('vance-toggle', { summary: b.summary, body: b.body });
    case 'dataview':
      return renderFence('vance-dataview', { source: b.source });
    case 'link-card': {
      const body: Record<string, unknown> = { href: b.href };
      if (b.title) body.title = b.title;
      if (b.description) body.description = b.description;
      return renderFence('vance-link', body);
    }
    case 'toc':
      return '```vance-toc\n```\n';
    case 'compose': {
      // Raw YAML body verbatim (not renderFence, which key/value-dumps).
      const body = b.yaml.endsWith('\n') ? b.yaml : b.yaml + '\n';
      const f = fenceFor(body);
      return f + 'vance-compose\n' + body + f + '\n';
    }
    case 'embed':
      return renderFence('vance-embed', { uri: b.uri });
    case 'form': {
      const body: Record<string, unknown> = { data: b.data };
      if (b.saveScript) body.saveScript = b.saveScript;
      if (b.session) body.session = true;
      if (b.form && Object.keys(b.form).length > 0) body.form = b.form;
      return renderFence('vance-form', body);
    }
    case 'input': {
      const body: Record<string, unknown> = { data: b.data, multiline: b.multiline };
      if (b.saveScript) body.saveScript = b.saveScript;
      if (b.session) body.session = true;
      return renderFence('vance-input', body);
    }
    case 'button':
      return renderFence('vance-button', {
        type: b.buttonType || 'script',
        title: b.title,
        script: b.script,
      });
    case 'columns': {
      // Outer fence must be longer than ANY inner fence so nested
      // code / vance-* / sub-columns blocks don't close the columns
      // prematurely. Default is 4 backticks (covers a single
      // triple-backtick block inside); we bump it dynamically if a
      // column contains another columns block (4-backticks inside →
      // need 5 outside, etc.).
      const innerBodies = b.columns.map((c) => serialize(c.blocks));
      const innerMaxFence = Math.max(
        3,
        ...innerBodies.map((s) => maxFenceLength(s)),
      );
      const fence = '`'.repeat(innerMaxFence + 1);
      let out = fence + 'vance-columns\n';
      b.columns.forEach((col, i) => {
        if (i > 0) {
          // Separator is an HTML-comment so it round-trips cleanly
          // and won't collide with anything a user could type
          // accidentally inside a column body. Leading + trailing
          // newlines anchor it to its own physical line so the regex
          // can't be tricked by inline text.
          out += col.width != null
            ? `\n<!--vance:column ${col.width}-->\n`
            : '\n<!--vance:column-->\n';
        }
        out += innerBodies[i];
      });
      if (!out.endsWith('\n')) out += '\n';
      out += fence + '\n';
      return out;
    }
    case 'custom': {
      // Attrs are the source of truth: with the extension present, derive
      // the body from attrs (so open-save and edit-save are identical and
      // byte-equal to the old core block). Without an extension (addon gone
      // since parse), fall back to the preserved rawBody verbatim.
      const ext = findBlockByFence(b.fence);
      const raw = ext ? bodyFromAttrs(ext, b.attrs) : b.rawBody;
      if (!raw) return '```' + b.fence + '\n```\n';
      const body = raw + (raw.endsWith('\n') ? '' : '\n');
      const f = fenceFor(body);
      return f + b.fence + '\n' + body + f + '\n';
    }
    case 'unknown-fence': {
      const body = b.body + (b.body.endsWith('\n') ? '' : '\n');
      const f = fenceFor(body);
      return f + b.info + '\n' + body + f + '\n';
    }
  }
}

/**
 * Longest contiguous run of backticks at the start of any line in
 * the given text. Used to size the outer fence of a columns block so
 * inner fenced blocks (code, vance-embed, nested columns) don't
 * close it prematurely. Returns 0 when no fence is present.
 */
function maxFenceLength(text: string): number {
  let max = 0;
  for (const line of text.split('\n')) {
    const m = /^(`{3,})/.exec(line);
    if (m && m[1].length > max) max = m[1].length;
  }
  return max;
}

function renderFence(info: string, body: Record<string, unknown>): string {
  const dumped = yaml.dump(body, {
    lineWidth: -1,
    noCompatMode: true,
    quotingType: '"',
    forceQuotes: false,
  });
  const f = fenceFor(dumped);
  return f + info + '\n' + dumped + f + '\n';
}

/**
 * A fence opener/closer long enough that no line inside {@code body} can close
 * it: one backtick longer than the longest triple-plus backtick run in the
 * body (minimum 3). Mirrors what the `columns` block already did, applied to
 * every fenced block so a body containing a ``` line round-trips instead of
 * being truncated by {@code findFenceClose} (which closes at a fence ≥ opener
 * length).
 */
function fenceFor(body: string): string {
  return '`'.repeat(Math.max(3, maxFenceLength(body) + 1));
}
