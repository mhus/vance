// Codec for `kind: slides` documents — parses an on-disk body
// (markdown, JSON or YAML) into a typed SlidesDocument and serializes
// it back. Round-trip-stable for known keys; unknown top-level keys
// pass through `extra`.
//
// Markdown is the canonical form: YAML front-matter + slide sections
// separated by CommonMark thematic breaks (`---`, `***`, `___` on a
// line by itself). Each slide is preserved as a raw Markdown string
// so Marpit-only constructs (HTML comments for directives, speaker
// notes) round-trip verbatim.
//
// See `specification/doc-kind-slides.md` for the schema.
import yaml from 'js-yaml';
import { dumpYamlBody, parseYamlBody, unwrapJsonMeta, wrapJsonMeta, } from './documentHeaderCodec';
export class SlidesCodecError extends Error {
    cause;
    constructor(message, cause) {
        super(message);
        this.cause = cause;
        this.name = 'SlidesCodecError';
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
export function parseSlides(body, mimeType) {
    if (isMarkdown(mimeType))
        return parseSlidesMarkdown(body);
    if (isJson(mimeType))
        return parseSlidesJson(body);
    if (isYaml(mimeType))
        return parseSlidesYaml(body);
    throw new SlidesCodecError(`Unsupported mime type for slides: ${mimeType}`);
}
export function serializeSlides(doc, mimeType) {
    if (isMarkdown(mimeType))
        return serializeSlidesMarkdown(doc);
    if (isJson(mimeType))
        return serializeSlidesJson(doc);
    if (isYaml(mimeType))
        return serializeSlidesYaml(doc);
    throw new SlidesCodecError(`Unsupported mime type for slides: ${mimeType}`);
}
/** Whether the codec can handle this mime type — drives the Slides
 *  tab activation in the document editor. */
export function isSlidesMime(mimeType) {
    if (!mimeType)
        return false;
    return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
}
// ── Markdown ─────────────────────────────────────────────────────────
const FENCE = '---';
/** A line that is a CommonMark thematic break used as the slide
 *  separator. Canonical form is `---`; we accept `***` and `___` too
 *  per CommonMark. We deliberately don't accept the spaced form
 *  (`- - -`) — Marpit doesn't either in practice. */
function isThematicBreak(line) {
    const t = line.trim();
    return t === '---' || t === '***' || t === '___';
}
/** A line that opens or closes a fenced code block. Tracking this is
 *  what keeps `---` inside a ` ``` ` block from being mis-read as a
 *  slide separator. We match the standard CommonMark forms: three or
 *  more backticks or tildes at the start of the line, possibly with an
 *  info string. */
function isCodeFence(line) {
    return /^(```+|~~~+)/.test(line.trimStart());
}
/** Parse a markdown body with optional `---`-fenced YAML front-matter
 *  and slide sections separated by thematic breaks. */
function parseSlidesMarkdown(body) {
    const lines = body.split(/\r?\n/);
    let cursor = 0;
    // Front matter — strict: first line must be exactly `---`. We treat
    // it as YAML (the spec mandates a nested `slides:` block, which the
    // server's MarkdownHeaderStrategy flattens but the client needs the
    // structured form).
    let frontMatterRaw = '';
    if (lines[0]?.trim() === FENCE) {
        cursor = 1;
        const fmLines = [];
        let closed = false;
        while (cursor < lines.length) {
            const l = lines[cursor];
            cursor++;
            if (l.trim() === FENCE) {
                closed = true;
                break;
            }
            fmLines.push(l);
        }
        if (!closed) {
            // No closing fence — treat as no front-matter and parse the
            // whole body as slides. This matches the server's behaviour
            // (MarkdownHeaderStrategy returns empty on unterminated fence).
            cursor = 0;
            fmLines.length = 0;
        }
        else {
            frontMatterRaw = fmLines.join('\n');
        }
    }
    // Parse front-matter as YAML for the structured `slides:` block.
    let kind = '';
    let slidesHeader = { extra: {} };
    const extra = {};
    if (frontMatterRaw) {
        let fm = {};
        try {
            const parsed = yaml.load(frontMatterRaw, { schema: yaml.JSON_SCHEMA });
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                fm = parsed;
            }
        }
        catch (e) {
            throw new SlidesCodecError('Invalid YAML front-matter: ' + (e instanceof Error ? e.message : String(e)), e);
        }
        if (typeof fm.kind === 'string')
            kind = fm.kind;
        if (isObject(fm.slides))
            slidesHeader = promoteSlidesHeader(fm.slides);
        for (const [k, v] of Object.entries(fm)) {
            if (k !== 'kind' && k !== 'slides')
                extra[k] = v;
        }
    }
    // Body — split on thematic breaks, but skip those inside fenced code
    // blocks. Empty slides (consecutive separators) are dropped; the user
    // can keep them in JSON/YAML if they really want a blank canvas.
    const items = [];
    let buf = [];
    let inCodeFence = false;
    for (let i = cursor; i < lines.length; i++) {
        const line = lines[i];
        if (isCodeFence(line))
            inCodeFence = !inCodeFence;
        if (!inCodeFence && isThematicBreak(line)) {
            pushSlide(items, buf);
            buf = [];
            continue;
        }
        buf.push(line);
    }
    pushSlide(items, buf);
    return { kind, items, slides: slidesHeader, extra };
}
function pushSlide(items, buf) {
    // Trim leading + trailing blank lines per slide. Internal blanks
    // stay — they are meaningful Markdown structure.
    let start = 0;
    let end = buf.length;
    while (start < end && buf[start].trim() === '')
        start++;
    while (end > start && buf[end - 1].trim() === '')
        end--;
    if (start >= end)
        return;
    items.push(buf.slice(start, end).join('\n'));
}
function serializeSlidesMarkdown(doc) {
    const out = [];
    // Front-matter — always emit `kind`. Emit `slides:` block only when
    // any header field is set (keeps minimal docs clean).
    out.push(FENCE);
    out.push(`kind: ${doc.kind || 'slides'}`);
    const slidesObj = slidesHeaderToObject(doc.slides);
    if (Object.keys(slidesObj).length > 0) {
        out.push('slides:');
        for (const [k, v] of Object.entries(slidesObj)) {
            out.push(`  ${k}: ${stringifyScalar(v)}`);
        }
    }
    for (const [k, v] of Object.entries(doc.extra)) {
        out.push(`${k}: ${stringifyScalar(v)}`);
    }
    out.push(FENCE);
    // Body — slides joined with a blank line, `---`, blank line.
    if (doc.items.length > 0) {
        out.push('');
        for (let i = 0; i < doc.items.length; i++) {
            // The slide body itself can contain trailing newlines; the join
            // already adds a single `\n`, so strip per-slide trailing blanks.
            out.push(doc.items[i].replace(/\s+$/u, ''));
            if (i < doc.items.length - 1) {
                out.push('');
                out.push(FENCE);
                out.push('');
            }
        }
    }
    return out.join('\n') + '\n';
}
function stringifyScalar(value) {
    if (value == null)
        return '';
    if (typeof value === 'string') {
        // Quote when the value contains characters YAML would otherwise
        // interpret as structure (`:`, leading `#`, leading/trailing space,
        // or a colon-followed pattern). We use plain double-quoting with
        // JSON-style escaping — js-yaml can read this back.
        if (/[:#\n"]/.test(value) || /^\s|\s$/.test(value)) {
            return JSON.stringify(value);
        }
        return value;
    }
    return String(value);
}
// ── JSON ─────────────────────────────────────────────────────────────
function parseSlidesJson(body) {
    if (body.trim() === '') {
        return { kind: 'slides', items: [], slides: { extra: {} }, extra: {} };
    }
    let parsed;
    try {
        parsed = JSON.parse(body);
    }
    catch (e) {
        throw new SlidesCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    if (!isObject(parsed)) {
        throw new SlidesCodecError('Top-level JSON must be an object');
    }
    return promoteToSlidesDocument(unwrapJsonMeta(parsed));
}
function serializeSlidesJson(doc) {
    const slidesObj = slidesHeaderToObject(doc.slides);
    const body = {};
    if (Object.keys(slidesObj).length > 0)
        body.slides = slidesObj;
    body.items = doc.items;
    for (const [k, v] of Object.entries(doc.extra)) {
        if (k !== 'slides' && k !== 'items')
            body[k] = v;
    }
    return JSON.stringify(wrapJsonMeta(doc.kind || 'slides', body), null, 2) + '\n';
}
// ── YAML ─────────────────────────────────────────────────────────────
function parseSlidesYaml(body) {
    if (body.trim() === '') {
        return { kind: 'slides', items: [], slides: { extra: {} }, extra: {} };
    }
    let merged;
    try {
        merged = parseYamlBody(body);
    }
    catch (e) {
        throw new SlidesCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    return promoteToSlidesDocument(merged);
}
function serializeSlidesYaml(doc) {
    const slidesObj = slidesHeaderToObject(doc.slides);
    const body = {};
    if (Object.keys(slidesObj).length > 0)
        body.slides = slidesObj;
    body.items = doc.items;
    for (const [k, v] of Object.entries(doc.extra)) {
        if (k !== 'slides' && k !== 'items')
            body[k] = v;
    }
    return dumpYamlBody(doc.kind || 'slides', body);
}
// ── Shared promotion ─────────────────────────────────────────────────
const HEADER_KEYS = new Set([
    'theme', 'aspect', 'paginate', 'defaultClass', 'header', 'footer',
]);
function promoteSlidesHeader(raw) {
    if (!isObject(raw))
        return { extra: {} };
    const h = { extra: {} };
    for (const [k, v] of Object.entries(raw)) {
        if (k === 'theme' && typeof v === 'string')
            h.theme = v;
        else if (k === 'aspect' && typeof v === 'string')
            h.aspect = clampAspect(v);
        else if (k === 'paginate' && typeof v === 'boolean')
            h.paginate = v;
        else if (k === 'defaultClass' && typeof v === 'string')
            h.defaultClass = v;
        else if (k === 'header' && typeof v === 'string')
            h.header = v;
        else if (k === 'footer' && typeof v === 'string')
            h.footer = v;
        else
            h.extra[k] = v;
    }
    return h;
}
function clampAspect(v) {
    return v === '4:3' ? '4:3' : '16:9';
}
function slidesHeaderToObject(h) {
    const out = {};
    if (h.theme !== undefined)
        out.theme = h.theme;
    if (h.aspect !== undefined)
        out.aspect = h.aspect;
    if (h.paginate !== undefined)
        out.paginate = h.paginate;
    if (h.defaultClass !== undefined)
        out.defaultClass = h.defaultClass;
    if (h.header !== undefined)
        out.header = h.header;
    if (h.footer !== undefined)
        out.footer = h.footer;
    for (const [k, v] of Object.entries(h.extra)) {
        if (!HEADER_KEYS.has(k))
            out[k] = v;
    }
    return out;
}
function promoteToSlidesDocument(obj) {
    const kind = typeof obj.kind === 'string' ? obj.kind : '';
    const slides = promoteSlidesHeader(obj.slides);
    const itemsRaw = obj.items;
    const items = [];
    if (Array.isArray(itemsRaw)) {
        for (const raw of itemsRaw) {
            if (typeof raw === 'string')
                items.push(raw);
            // Object slides are out of scope for v1 — drop silently rather
            // than throw, so a structurally rich future format degrades to
            // empty rather than blowing up the editor.
        }
    }
    const extra = {};
    for (const [k, v] of Object.entries(obj)) {
        if (k !== 'kind' && k !== 'slides' && k !== 'items')
            extra[k] = v;
    }
    return { kind, items, slides, extra };
}
function isObject(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
//# sourceMappingURL=slidesCodec.js.map