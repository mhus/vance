// Block list → Markdown (TS counterpart of CanvasSerializer.java).

import yaml from 'js-yaml';
import type { Block, CanvasDocument } from './blocks';

/**
 * Render a full canvas document (front-matter + body).
 */
export function serializeDocument(doc: CanvasDocument): string {
  let out = '---\n$meta:\n  kind: canvas\n';
  if (doc.title && doc.title.trim().length > 0) {
    out += `title: ${escapeYaml(doc.title)}\n`;
  }
  if (doc.description && doc.description.trim().length > 0) {
    out += `description: ${escapeYaml(doc.description)}\n`;
  }
  if (doc.icon && doc.icon.trim().length > 0) {
    out += `icon: ${escapeYaml(doc.icon)}\n`;
  }
  if (doc.cover && doc.cover.trim().length > 0) {
    out += `cover: ${escapeYaml(doc.cover)}\n`;
  }
  out += '---\n';
  out += serialize(doc.blocks);
  return out;
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
    case 'code':
      return (
        '```' +
        (b.lang ?? '') +
        '\n' +
        b.code +
        (b.code.endsWith('\n') ? '' : '\n') +
        '```\n'
      );
    case 'divider':
      return '---\n';
    case 'image':
      return `![${b.alt}](${b.src})\n`;
    case 'table': {
      const head = '| ' + b.headers.join(' | ') + ' |';
      const div = '| ' + b.headers.map(() => '---').join(' | ') + ' |';
      const rows = b.rows.map((r) => '| ' + r.join(' | ') + ' |');
      return [head, div, ...rows].join('\n') + '\n';
    }
    case 'callout': {
      const body: Record<string, unknown> = { severity: b.severity };
      if (b.title) body.title = b.title;
      if (b.body) body.body = b.body;
      return renderFence('vance-callout', body);
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
    case 'unknown-fence':
      return '```' + b.info + '\n' + b.body + (b.body.endsWith('\n') ? '' : '\n') + '```\n';
  }
}

function renderFence(info: string, body: Record<string, unknown>): string {
  const dumped = yaml.dump(body, {
    lineWidth: -1,
    noCompatMode: true,
    quotingType: '"',
    forceQuotes: false,
  });
  return '```' + info + '\n' + dumped + '```\n';
}

function escapeYaml(value: string): string {
  if (
    value.includes('\n') ||
    value.includes(':') ||
    value.includes('#') ||
    value.includes('"') ||
    value.includes("'")
  ) {
    return '"' + value.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  }
  return value;
}
