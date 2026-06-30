// Bridge between Block[] (markdown-shaped data model) and ProseMirror
// JSON content. Inline-formatting (bold/italic/inline-code/inline-link/
// strike) is parsed from Markdown syntax into ProseMirror marks and
// re-serialized on save — see ./inline.ts for the inline codec.

import type { JSONContent } from '@tiptap/core';
import type { Block } from './blocks';
import { parseInlineToProseMirror, serializeProseMirrorInline } from './inline';

export function blocksToContent(blocks: Block[]): JSONContent[] {
  return blocks.map(blockToNode);
}

export function contentToBlocks(content: JSONContent[] | undefined): Block[] {
  if (!content) return [];
  return content.flatMap(nodeToBlock);
}

function blockToNode(b: Block): JSONContent {
  switch (b.kind) {
    case 'paragraph':
      return paraNode(b.text);
    case 'heading':
      return {
        type: 'heading',
        attrs: { level: b.level },
        content: parseInlineToProseMirror(b.text),
      };
    case 'bullet-list':
      return {
        type: 'bulletList',
        content: b.items.map((item) => listItemNode(item)),
      };
    case 'numbered-list':
      return {
        type: 'orderedList',
        content: b.items.map((item) => listItemNode(item)),
      };
    case 'todo':
      return {
        type: 'taskList',
        content: b.items.map((it) => ({
          type: 'taskItem',
          attrs: { checked: it.checked },
          content: [paraNode(it.text)],
        })),
      };
    case 'quote':
      return {
        type: 'blockquote',
        content: b.text.split('\n').map((l) => paraNode(l)),
      };
    case 'code':
      return {
        type: 'codeBlock',
        attrs: { language: b.lang ?? null },
        content: b.code.length > 0 ? [{ type: 'text', text: b.code }] : [],
      };
    case 'divider':
      return { type: 'horizontalRule' };
    case 'image':
      return {
        type: 'image',
        attrs: { src: b.src, alt: b.alt, width: b.width ?? null },
      };
    case 'table': {
      const rows: JSONContent[] = [];
      if (b.headers.length > 0) {
        rows.push({
          type: 'tableRow',
          content: b.headers.map((h) => ({
            type: 'tableHeader',
            content: [paraNode(h)],
          })),
        });
      }
      for (const r of b.rows) {
        rows.push({
          type: 'tableRow',
          content: r.map((c) => ({ type: 'tableCell', content: [paraNode(c)] })),
        });
      }
      return { type: 'table', content: rows };
    }
    case 'callout':
      return {
        type: 'vanceCallout',
        attrs: { severity: b.severity, title: b.title, body: b.body },
      };
    case 'toggle':
      return {
        type: 'vanceToggle',
        attrs: { summary: b.summary, body: b.body },
      };
    case 'dataview':
      return { type: 'vanceDataview', attrs: { source: b.source } };
    case 'link-card':
      return {
        type: 'vanceLink',
        attrs: { href: b.href, title: b.title, description: b.description },
      };
    case 'toc':
      return { type: 'vanceToc' };
    case 'embed':
      return { type: 'vanceEmbed', attrs: { uri: b.uri } };
    case 'form':
      return { type: 'vanceForm', attrs: { config: b.config } };
    case 'columns':
      return {
        type: 'vanceColumns',
        content: b.columns.map((col) => ({
          type: 'vanceColumn',
          attrs: { width: col.width },
          content:
            col.blocks.length > 0
              ? blocksToContent(col.blocks)
              : [{ type: 'paragraph' }],
        })),
      };
    case 'unknown-fence':
      return { type: 'vanceUnknownFence', attrs: { info: b.info, body: b.body } };
  }
}

function nodeToBlock(node: JSONContent): Block[] {
  switch (node.type) {
    case 'paragraph':
      return [{ kind: 'paragraph', text: collectInline(node) }];
    case 'heading': {
      const level = clampHeading(node.attrs?.level);
      return [{ kind: 'heading', level, text: collectInline(node) }];
    }
    case 'bulletList':
      return [
        {
          kind: 'bullet-list',
          items: (node.content ?? []).map((li) => collectInline(li)),
        },
      ];
    case 'orderedList':
      return [
        {
          kind: 'numbered-list',
          items: (node.content ?? []).map((li) => collectInline(li)),
        },
      ];
    case 'taskList':
      return [
        {
          kind: 'todo',
          items: (node.content ?? []).map((it) => ({
            checked: Boolean(it.attrs?.checked),
            text: collectInline(it),
          })),
        },
      ];
    case 'blockquote': {
      const lines = (node.content ?? []).map((p) => collectInline(p));
      return [{ kind: 'quote', text: lines.join('\n') }];
    }
    case 'codeBlock': {
      const code = collectText(node);
      return [
        {
          kind: 'code',
          lang: (node.attrs?.language as string | null) ?? null,
          code,
        },
      ];
    }
    case 'horizontalRule':
      return [{ kind: 'divider' }];
    case 'image': {
      const width = node.attrs?.width;
      const widthValid = width === 'small' || width === 'medium'
        || width === 'large' || width === 'full';
      return [
        {
          kind: 'image',
          alt: (node.attrs?.alt as string) ?? '',
          src: (node.attrs?.src as string) ?? '',
          ...(widthValid ? { width } : {}),
        },
      ];
    }
    case 'table': {
      const rows = node.content ?? [];
      let headers: string[] = [];
      const dataRows: string[][] = [];
      let first = true;
      for (const row of rows) {
        const cells = (row.content ?? []).map((c) => collectInline(c));
        const isHeader = (row.content ?? []).some((c) => c.type === 'tableHeader');
        if (first && isHeader) {
          headers = cells;
        } else {
          dataRows.push(cells);
        }
        first = false;
      }
      return [{ kind: 'table', headers, rows: dataRows }];
    }
    case 'vanceCallout':
      return [
        {
          kind: 'callout',
          severity: (node.attrs?.severity as string) ?? 'info',
          title: (node.attrs?.title as string | null) ?? null,
          body: (node.attrs?.body as string) ?? '',
        },
      ];
    case 'vanceToggle':
      return [
        {
          kind: 'toggle',
          summary: (node.attrs?.summary as string) ?? '',
          body: (node.attrs?.body as string) ?? '',
        },
      ];
    case 'vanceDataview':
      return [
        { kind: 'dataview', source: (node.attrs?.source as string) ?? '' },
      ];
    case 'vanceLink':
      return [
        {
          kind: 'link-card',
          href: (node.attrs?.href as string) ?? '',
          title: (node.attrs?.title as string | null) ?? null,
          description: (node.attrs?.description as string | null) ?? null,
        },
      ];
    case 'vanceToc':
      return [{ kind: 'toc' }];
    case 'vanceEmbed':
      return [{ kind: 'embed', uri: (node.attrs?.uri as string) ?? '' }];
    case 'vanceForm':
      return [{ kind: 'form', config: (node.attrs?.config as string) ?? '' }];
    case 'vanceColumns': {
      const cols = (node.content ?? []).map((colNode) => ({
        blocks: contentToBlocks(colNode.content ?? []),
        width:
          typeof colNode.attrs?.width === 'number'
            ? (colNode.attrs.width as number)
            : null,
      }));
      return [{ kind: 'columns', columns: cols }];
    }
    case 'vanceUnknownFence':
      return [
        {
          kind: 'unknown-fence',
          info: (node.attrs?.info as string) ?? '',
          body: (node.attrs?.body as string) ?? '',
        },
      ];
    default:
      return [];
  }
}

function paraNode(text: string): JSONContent {
  return { type: 'paragraph', content: parseInlineToProseMirror(text) };
}

function listItemNode(text: string): JSONContent {
  return { type: 'listItem', content: [paraNode(text)] };
}

/**
 * Plain-text collector — flat character content, no mark-awareness.
 * Used for code blocks and table-cell-without-inline contexts.
 */
function collectText(node: JSONContent | undefined): string {
  if (!node) return '';
  if (node.type === 'text' && typeof node.text === 'string') return node.text;
  if (Array.isArray(node.content)) {
    return node.content.map((c) => collectText(c)).join(node.type === 'paragraph' ? '' : '\n');
  }
  return '';
}

/**
 * Inline-aware collector — preserves mark formatting by re-emitting
 * Markdown syntax around mark-wrapped text nodes. Used for paragraphs,
 * headings, list items, blockquotes, todo items and table cells —
 * anything where the Block model stores Markdown-shaped strings.
 */
function collectInline(node: JSONContent | undefined): string {
  if (!node) return '';
  if (Array.isArray(node.content)) {
    if (node.type === 'paragraph' || node.type === 'heading') {
      return serializeProseMirrorInline(node.content);
    }
    if (node.type === 'listItem' || node.type === 'taskItem') {
      // listItem wraps one or more paragraphs (often one). Serialize
      // each paragraph's inline content, join with newlines for the
      // rare multi-paragraph case.
      return node.content
        .map((c) => collectInline(c))
        .filter((s) => s.length > 0)
        .join('\n');
    }
    if (node.type === 'blockquote' || node.type === 'tableCell' || node.type === 'tableHeader') {
      return node.content.map((c) => collectInline(c)).join('\n');
    }
    return serializeProseMirrorInline(node.content);
  }
  if (node.type === 'text' && typeof node.text === 'string') {
    return serializeProseMirrorInline([node]);
  }
  return '';
}

function clampHeading(level: unknown): 1 | 2 | 3 {
  const n = typeof level === 'number' ? level : Number(level);
  if (n <= 1) return 1;
  if (n >= 3) return 3;
  return 2;
}
