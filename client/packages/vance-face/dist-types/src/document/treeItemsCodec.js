// Codec for `kind: tree` documents — parses an on-disk body
// (markdown, JSON or YAML) into a typed TreeDocument and serializes
// it back. Mirrors the design of {@link ./listItemsCodec} but adds
// recursive `children` per item.
//
// See `specification/doc-kind-tree.md` for the schema and the
// markdown indent-nesting rules.
import { dumpYamlBody, parseYamlBody, unwrapJsonMeta, wrapJsonMeta, } from '@vance/shared';
export class TreeCodecError extends Error {
    cause;
    constructor(message, cause) {
        super(message);
        this.cause = cause;
        this.name = 'TreeCodecError';
    }
}
// ── MIME helpers ─────────────────────────────────────────────────────
function isMarkdown(mime) {
    return mime === 'text/markdown' || mime === 'text/x-markdown';
}
function isJson(mime) {
    return mime === 'application/json';
}
function isYaml(mime) {
    return mime === 'application/yaml'
        || mime === 'application/x-yaml'
        || mime === 'text/yaml'
        || mime === 'text/x-yaml';
}
// ── Public API ───────────────────────────────────────────────────────
export function parseTree(body, mimeType) {
    if (isMarkdown(mimeType))
        return parseTreeMarkdown(body);
    if (isJson(mimeType))
        return parseTreeJson(body);
    if (isYaml(mimeType))
        return parseTreeYaml(body);
    throw new TreeCodecError(`Unsupported mime type for tree: ${mimeType}`);
}
export function serializeTree(doc, mimeType) {
    if (isMarkdown(mimeType))
        return serializeTreeMarkdown(doc);
    if (isJson(mimeType))
        return serializeTreeJson(doc);
    if (isYaml(mimeType))
        return serializeTreeYaml(doc);
    throw new TreeCodecError(`Unsupported mime type for tree: ${mimeType}`);
}
/** Whether the codec can handle this mime type. */
export function isTreeMime(mimeType) {
    if (!mimeType)
        return false;
    return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
}
// ── Markdown ─────────────────────────────────────────────────────────
const MD_FENCE = '---';
/**
 * Parse a markdown body with optional front-matter and a nested bullet
 * list. Indent depth is computed from leading whitespace (tabs = 4
 * spaces); each indent unit of 2 spaces is one nesting level. Items
 * deeper than {@code lastDepth + 1} are clamped to that — no
 * skip-level nodes.
 *
 * Tolerance for inline `mindmap` fences: LLMs sometimes drop the
 * bullet markers and use pure indentation (Mermaid-mindmap style,
 * including the `root((X))`/`root[X]`/`root(X)` wrappers). When the
 * body has no bullets at all, the parser falls back to an
 * indent-only path so the fence still renders. Bodies with a single
 * bullet anywhere fall back to the strict bullet path — mixed bodies
 * stay deterministic.
 */
function parseTreeMarkdown(body) {
    const lines = body.split(/\r?\n/);
    let cursor = 0;
    // Front matter
    const extra = {};
    let kind = '';
    if (lines[0]?.trim() === MD_FENCE) {
        cursor = 1;
        while (cursor < lines.length && lines[cursor].trim() !== MD_FENCE) {
            const line = lines[cursor].trim();
            cursor++;
            if (!line || line.startsWith('#'))
                continue;
            const colon = line.indexOf(':');
            if (colon <= 0)
                continue;
            const key = line.slice(0, colon).trim();
            const value = line.slice(colon + 1).trim();
            if (key === 'kind') {
                kind = value;
            }
            else {
                extra[key] = value;
            }
        }
        if (cursor < lines.length && lines[cursor].trim() === MD_FENCE)
            cursor++;
    }
    const bodyLines = lines.slice(cursor);
    const hasBullet = bodyLines.some((l) => /^\s*[-*]\s+/.test(l));
    const items = hasBullet
        ? parseBulletBody(bodyLines)
        : parseIndentedBody(bodyLines);
    return { kind, items, extra };
}
/** Strict bullet parser per spec §3.1. Indent depth from leading
 *  whitespace (tabs = 4 spaces); 2 spaces per nesting level; deeper
 *  items clamped to {@code lastDepth + 1}; non-bullet indented lines
 *  attach as a continuation to the previous bullet's {@code text}. */
function parseBulletBody(lines) {
    const root = [];
    const openLevels = [{ depth: -1, list: root }];
    let lastItem = null;
    let lastDepth = -1;
    for (const raw of lines) {
        if (raw.trim() === '') {
            lastItem = null;
            continue;
        }
        const bullet = raw.match(/^(\s*)[-*]\s+(.*)$/);
        if (bullet) {
            const indent = countIndent(bullet[1]);
            let depth = Math.floor(indent / 2);
            if (depth > lastDepth + 1)
                depth = lastDepth + 1;
            if (depth < 0)
                depth = 0;
            while (openLevels.length > 1
                && openLevels[openLevels.length - 1].depth >= depth) {
                openLevels.pop();
            }
            const parent = openLevels[openLevels.length - 1].list;
            const item = { text: bullet[2], children: [], extra: {} };
            parent.push(item);
            openLevels.push({ depth, list: item.children });
            lastItem = item;
            lastDepth = depth;
            continue;
        }
        if (lastItem && /^\s+\S/.test(raw)) {
            const indent = countIndent(raw.match(/^(\s*)/)?.[1] ?? '');
            if (indent >= (lastDepth + 1) * 2) {
                const stripped = raw.replace(/^\s+/, '');
                lastItem.text += '\n' + stripped;
            }
        }
    }
    return root;
}
/** Bullet-less indented-tree parser. Engaged only when the whole body
 *  contains no bullet line — covers LLM Mermaid-mindmap-style output
 *  where each line is the node text and depth comes from indent
 *  alone. Strips Mermaid `root((X))` / `root[X]` / `root(X)` wrappers
 *  on depth-0 lines. */
function parseIndentedBody(lines) {
    const root = [];
    const openLevels = [{ depth: -1, list: root }];
    let lastDepth = -1;
    for (const raw of lines) {
        if (raw.trim() === '')
            continue;
        const indentPrefix = raw.match(/^(\s*)/)?.[1] ?? '';
        const indent = countIndent(indentPrefix);
        let depth = Math.floor(indent / 2);
        if (depth > lastDepth + 1)
            depth = lastDepth + 1;
        if (depth < 0)
            depth = 0;
        let text = raw.slice(indentPrefix.length).trimEnd();
        if (depth === 0)
            text = stripMermaidRoot(text);
        if (text === '')
            continue;
        while (openLevels.length > 1
            && openLevels[openLevels.length - 1].depth >= depth) {
            openLevels.pop();
        }
        const parent = openLevels[openLevels.length - 1].list;
        const item = { text, children: [], extra: {} };
        parent.push(item);
        openLevels.push({ depth, list: item.children });
        lastDepth = depth;
    }
    return root;
}
/** Mermaid mindmap roots come in three shapes: {@code root((X))}
 *  (cloud), {@code root[X]} (box), {@code root(X)} (rounded). Strip
 *  the wrapper so the inner label is what we keep. */
function stripMermaidRoot(text) {
    const m = text.match(/^root\s*\(\((.+)\)\)\s*$/)
        ?? text.match(/^root\s*\[(.+)\]\s*$/)
        ?? text.match(/^root\s*\((.+)\)\s*$/);
    return m ? m[1].trim() : text;
}
function serializeTreeMarkdown(doc) {
    const out = [];
    out.push(MD_FENCE);
    out.push(`kind: ${doc.kind || 'tree'}`);
    for (const [key, value] of Object.entries(doc.extra)) {
        out.push(`${key}: ${stringifyMdExtra(value)}`);
    }
    out.push(MD_FENCE);
    emitMdItems(doc.items, 0, out);
    return out.join('\n') + '\n';
}
function emitMdItems(items, depth, out) {
    const indent = '  '.repeat(depth);
    for (const item of items) {
        const lines = item.text.split('\n');
        out.push(`${indent}- ${lines[0] ?? ''}`);
        for (let i = 1; i < lines.length; i++) {
            out.push(`${indent}  ${lines[i]}`);
        }
        if (item.children.length > 0) {
            emitMdItems(item.children, depth + 1, out);
        }
    }
}
function stringifyMdExtra(value) {
    if (value == null)
        return '';
    if (typeof value === 'string')
        return value;
    return String(value);
}
/** Tabs count as 4 spaces; spaces count as themselves. Anything else
 *  is treated as 0 (defensive). */
function countIndent(prefix) {
    let n = 0;
    for (const c of prefix) {
        if (c === ' ')
            n++;
        else if (c === '\t')
            n += 4;
    }
    return n;
}
// ── JSON ─────────────────────────────────────────────────────────────
function parseTreeJson(body) {
    if (body.trim() === '') {
        return { kind: 'tree', items: [], extra: {} };
    }
    let parsed;
    try {
        parsed = JSON.parse(body);
    }
    catch (e) {
        throw new TreeCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    if (!isObject(parsed)) {
        throw new TreeCodecError('Top-level JSON must be an object');
    }
    return promoteToTreeDocument(unwrapJsonMeta(parsed));
}
function serializeTreeJson(doc) {
    return JSON.stringify(wrapJsonMeta(doc.kind || 'tree', {
        items: doc.items.map(itemToObject),
        ...doc.extra,
    }), null, 2) + '\n';
}
// ── YAML ─────────────────────────────────────────────────────────────
function parseTreeYaml(body) {
    if (body.trim() === '') {
        return { kind: 'tree', items: [], extra: {} };
    }
    let merged;
    try {
        merged = parseYamlBody(body);
    }
    catch (e) {
        throw new TreeCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    return promoteToTreeDocument(merged);
}
function serializeTreeYaml(doc) {
    return dumpYamlBody(doc.kind || 'tree', {
        items: doc.items.map(itemToObject),
        ...doc.extra,
    });
}
// ── Shared promotion logic (json + yaml share the object shape) ─────
function promoteToTreeDocument(obj) {
    const kind = typeof obj.kind === 'string' ? obj.kind : '';
    const items = promoteItems(obj.items);
    const { kind: _k, items: _i, ...extra } = obj;
    return { kind, items, extra };
}
function promoteItems(raw) {
    if (!Array.isArray(raw))
        return [];
    const out = [];
    for (const r of raw) {
        if (!isObject(r))
            continue; // String-shorthand not allowed for tree (spec §3.3)
        const text = typeof r.text === 'string' ? r.text : '';
        const children = promoteItems(r.children);
        const { text: _t, children: _c, ...extra } = r;
        out.push({ text, children, extra });
    }
    return out;
}
function itemToObject(item) {
    return {
        text: item.text,
        children: item.children.map(itemToObject),
        ...item.extra,
    };
}
function isObject(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
//# sourceMappingURL=treeItemsCodec.js.map