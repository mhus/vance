// Adapter from `kind: mindmap` documents to markmap-flavoured
// markdown. Mindmap reuses the tree codec untouched — see
// `specification/doc-kind-mindmap.md` §3.4 (Codec-Auswirkung).
//
// The adapter pulls mindmap-specific per-node fields (`icon`,
// `link`) out of `TreeItem.extra`, because the tree codec parses
// unknown json/yaml keys into that pass-through map.
/** Convert a `TreeDocument` (parsed under `kind: mindmap`) into the
 *  markdown form that markmap-lib's Transformer accepts.
 *
 *  Per-item mapping (see spec §5.4):
 *  - Indent: depth × 2 spaces, then `- `.
 *  - Topic: `text`, optionally wrapped in `[…](link)`, optionally
 *    prefixed with `<icon> ` outside the link.
 *  - Multi-line text: continuation lines go after the bullet at
 *    `indent + 2` spaces, like the tree codec writes them. */
export function treeToMarkmapMarkdown(doc) {
    const opts = readMindmapOptions(doc);
    const lines = [];
    if (hasFrontMatter(opts)) {
        lines.push('---');
        lines.push('markmap:');
        if (opts.colorFreezeLevel !== undefined)
            lines.push(`  colorFreezeLevel: ${opts.colorFreezeLevel}`);
        if (opts.maxWidth !== undefined)
            lines.push(`  maxWidth: ${opts.maxWidth}`);
        if (opts.initialExpandLevel !== undefined)
            lines.push(`  initialExpandLevel: ${opts.initialExpandLevel}`);
        lines.push('---');
        lines.push('');
    }
    for (const item of doc.items) {
        walk(item, 0, lines);
    }
    return lines.join('\n');
}
/** Read the document-level `mindmap:` block from the parsed
 *  document's `extra` map, plus a couple of pass-throughs that map
 *  one-to-one onto markmap front-matter keys. */
function readMindmapOptions(doc) {
    const block = doc.extra?.mindmap;
    const out = {};
    if (block && typeof block === 'object') {
        const lvl = block.initialExpandLevel;
        if (typeof lvl === 'number')
            out.initialExpandLevel = lvl;
        const freeze = block.colorFreezeLevel;
        if (typeof freeze === 'number')
            out.colorFreezeLevel = freeze;
        const mw = block.maxWidth;
        if (typeof mw === 'number')
            out.maxWidth = mw;
    }
    return out;
}
function hasFrontMatter(opts) {
    return opts.initialExpandLevel !== undefined
        || opts.colorFreezeLevel !== undefined
        || opts.maxWidth !== undefined;
}
function walk(item, depth, lines) {
    const indent = '  '.repeat(depth);
    const text = item.text ?? '';
    const icon = stringField(item, 'icon');
    const link = stringField(item, 'link');
    // Build the per-item topic. Multi-line text is handled by
    // splitting at the first newline — only the first line follows
    // the `- ` bullet; subsequent lines are continuation-indented.
    const firstNewline = text.indexOf('\n');
    const head = firstNewline === -1 ? text : text.slice(0, firstNewline);
    const tail = firstNewline === -1 ? '' : text.slice(firstNewline + 1);
    const renderedHead = decorate(head, icon, link);
    lines.push(`${indent}- ${renderedHead}`);
    if (tail) {
        for (const cont of tail.split('\n')) {
            lines.push(`${indent}  ${cont}`);
        }
    }
    for (const child of item.children) {
        walk(child, depth + 1, lines);
    }
}
/** Wrap topic text into a markdown link (when `link` is set) and
 *  prefix it with the icon. The icon stays outside the link so
 *  Emoji/Glyphs don't become clickable underlined text — only the
 *  topic itself is the click target. */
function decorate(text, icon, link) {
    let body;
    if (link) {
        const linkText = text || link;
        body = `[${escapeMd(linkText)}](${link})`;
    }
    else {
        body = escapeMd(text);
    }
    return icon ? `${icon} ${body}` : body;
}
/** Conservative markdown-escape for the topic body. We only escape
 *  the structural characters that would break a single-line bullet
 *  (`[`, `]`, `\\`); markmap renders inline markdown so leaving
 *  bold/italic/code intact is correct. */
function escapeMd(s) {
    return s.replace(/\\/g, '\\\\').replace(/\[/g, '\\[').replace(/\]/g, '\\]');
}
function stringField(item, key) {
    const v = item.extra?.[key];
    return typeof v === 'string' && v.length > 0 ? v : undefined;
}
//# sourceMappingURL=mindmapAdapter.js.map