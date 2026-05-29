// Codec for `kind: checklist` documents — parses an on-disk body
// (markdown, JSON or YAML) into a typed ChecklistDocument and
// serializes it back. Mirrors the Java ChecklistCodec byte-for-byte.
//
// Status chars in markdown follow `specification/doc-kind-checklist.md`
// §2.2. Unknown chars round-trip via `extra._statusChar`. Priority
// lives as a trailing `#prio:high|low` tag in markdown, as a typed
// `priority` field in JSON/YAML.
//
// See `specification/doc-kind-checklist.md` for the schema.
import { dumpYamlBody, parseYamlBody, unwrapJsonMeta, wrapJsonMeta, } from './documentHeaderCodec';
/** Reserved per-item extra key that preserves a non-standard Markdown
 *  checkbox char across a round-trip. */
export const STATUS_CHAR_EXTRA_KEY = '_statusChar';
export class ChecklistCodecError extends Error {
    cause;
    constructor(message, cause) {
        super(message);
        this.cause = cause;
        this.name = 'ChecklistCodecError';
    }
}
// ── Char ↔ status mapping (spec §2.2) ────────────────────────────────
const CHAR_TO_STATUS = {
    ' ': 'open',
    'x': 'done',
    'X': 'done',
    '~': 'in_progress',
    '/': 'review',
    '!': 'blocked',
    '?': 'needs_info',
    '-': 'deferred',
    '>': 'delegated',
    '<': 'waiting',
};
const STATUS_TO_CHAR = {
    open: ' ',
    done: 'x',
    in_progress: '~',
    review: '/',
    blocked: '!',
    needs_info: '?',
    deferred: '-',
    delegated: '>',
    waiting: '<',
};
const WIRE_STATUSES = new Set([
    'open', 'done', 'in_progress', 'review', 'blocked',
    'needs_info', 'deferred', 'delegated', 'waiting',
]);
const WIRE_PRIORITIES = new Set(['high', 'low']);
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
export function parseChecklist(body, mimeType) {
    if (isMarkdown(mimeType))
        return parseChecklistMarkdown(body);
    if (isJson(mimeType))
        return parseChecklistJson(body);
    if (isYaml(mimeType))
        return parseChecklistYaml(body);
    throw new ChecklistCodecError(`Unsupported mime type for checklist: ${mimeType}`);
}
export function serializeChecklist(doc, mimeType) {
    if (isMarkdown(mimeType))
        return serializeChecklistMarkdown(doc);
    if (isJson(mimeType))
        return serializeChecklistJson(doc);
    if (isYaml(mimeType))
        return serializeChecklistYaml(doc);
    throw new ChecklistCodecError(`Unsupported mime type for checklist: ${mimeType}`);
}
export function isChecklistMime(mimeType) {
    if (!mimeType)
        return false;
    return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
}
// ── Markdown ─────────────────────────────────────────────────────────
const MD_FENCE = '---';
/** Matches a bullet with optional checkbox. Group 1 is the status char
 *  (or undefined for plain bullets); group 2 is the text. */
const BULLET_PATTERN = /^[-*] (?:\[(.)?] )?(.*)$/;
/** Trailing #prio:high|low at the very end of an item's first line. */
const PRIO_TAG_PATTERN = /\s*#prio:(high|low)\s*$/i;
function parseChecklistMarkdown(body) {
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
    // Bullet list
    const items = [];
    let current = null;
    for (let i = cursor; i < lines.length; i++) {
        const raw = lines[i];
        if (raw.trim() === '') {
            current = null;
            continue;
        }
        const m = BULLET_PATTERN.exec(raw);
        if (m) {
            const charGroup = m[1];
            let text = m[2];
            const itemExtra = {};
            let status = 'open';
            if (charGroup === undefined) {
                // Plain bullet, no checkbox. Default OPEN.
            }
            else {
                const mapped = CHAR_TO_STATUS[charGroup];
                if (mapped !== undefined) {
                    status = mapped;
                }
                else {
                    itemExtra[STATUS_CHAR_EXTRA_KEY] = charGroup;
                }
            }
            // Trailing #prio tag — pull it off the first line.
            let priority;
            const prio = text.match(PRIO_TAG_PATTERN);
            if (prio) {
                priority = prio[1].toLowerCase();
                text = text.slice(0, prio.index);
            }
            current = { text, status, priority, extra: itemExtra };
            items.push(current);
            continue;
        }
        // Continuation: ≥2 leading spaces, append to previous item's text.
        if (current && /^\s{2,}/.test(raw)) {
            current.text += '\n' + raw.replace(/^\s{2,}/, '');
            continue;
        }
        // Anything else outside the list area is dropped (v1 limit).
    }
    return { kind: kind || 'checklist', items, extra };
}
function serializeChecklistMarkdown(doc) {
    const out = [];
    out.push(MD_FENCE);
    out.push(`kind: ${doc.kind || 'checklist'}`);
    for (const [key, value] of Object.entries(doc.extra)) {
        out.push(`${key}: ${stringifyMdExtra(value)}`);
    }
    out.push(MD_FENCE);
    for (const item of doc.items) {
        const char = pickMarkdownChar(item);
        const lines = item.text.split('\n');
        let first = `- [${char}] ${lines[0] ?? ''}`;
        if (item.priority) {
            first += ` #prio:${item.priority}`;
        }
        out.push(first);
        for (let i = 1; i < lines.length; i++) {
            out.push(`  ${lines[i]}`);
        }
    }
    return out.join('\n') + '\n';
}
function pickMarkdownChar(item) {
    const custom = item.extra[STATUS_CHAR_EXTRA_KEY];
    if (typeof custom === 'string' && custom.length > 0) {
        return custom.charAt(0);
    }
    return STATUS_TO_CHAR[item.status] ?? ' ';
}
function stringifyMdExtra(value) {
    if (value == null)
        return '';
    if (typeof value === 'string')
        return value;
    return String(value);
}
// ── JSON ─────────────────────────────────────────────────────────────
function parseChecklistJson(body) {
    if (body.trim() === '') {
        return { kind: 'checklist', items: [], extra: {} };
    }
    let parsed;
    try {
        parsed = JSON.parse(body);
    }
    catch (e) {
        throw new ChecklistCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    if (!isObject(parsed)) {
        throw new ChecklistCodecError('Top-level JSON must be an object');
    }
    return promoteToChecklistDocument(unwrapJsonMeta(parsed));
}
function serializeChecklistJson(doc) {
    return JSON.stringify(wrapJsonMeta(doc.kind || 'checklist', {
        items: doc.items.map(itemToObject),
        ...doc.extra,
    }), null, 2) + '\n';
}
// ── YAML ─────────────────────────────────────────────────────────────
function parseChecklistYaml(body) {
    if (body.trim() === '') {
        return { kind: 'checklist', items: [], extra: {} };
    }
    let merged;
    try {
        merged = parseYamlBody(body);
    }
    catch (e) {
        throw new ChecklistCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    return promoteToChecklistDocument(merged);
}
function serializeChecklistYaml(doc) {
    return dumpYamlBody(doc.kind || 'checklist', {
        items: doc.items.map(itemToObject),
        ...doc.extra,
    });
}
// ── Shared promotion logic ──────────────────────────────────────────
function promoteToChecklistDocument(obj) {
    const kind = typeof obj.kind === 'string' ? obj.kind : '';
    const itemsRaw = obj.items;
    const items = [];
    if (Array.isArray(itemsRaw)) {
        for (const raw of itemsRaw) {
            if (typeof raw === 'string') {
                items.push({ text: raw, status: 'open', extra: {} });
                continue;
            }
            if (!isObject(raw))
                continue;
            const promoted = promoteItem(raw);
            if (promoted)
                items.push(promoted);
        }
    }
    const { kind: _k, items: _i, ...extra } = obj;
    return { kind, items, extra };
}
function promoteItem(raw) {
    const text = raw.text;
    if (typeof text !== 'string')
        return null;
    let status = 'open';
    if (typeof raw.status === 'string') {
        const lower = raw.status.toLowerCase();
        if (WIRE_STATUSES.has(lower)) {
            status = lower;
        }
    }
    let priority;
    if (typeof raw.priority === 'string') {
        const lower = raw.priority.toLowerCase();
        if (WIRE_PRIORITIES.has(lower)) {
            priority = lower;
        }
    }
    const extra = {};
    for (const [key, value] of Object.entries(raw)) {
        if (key === 'text' || key === 'status' || key === 'priority')
            continue;
        extra[key] = value;
    }
    // Preserve non-standard priority strings so they round-trip.
    if (!priority && typeof raw.priority === 'string' && raw.priority.length > 0) {
        extra.priority = raw.priority;
    }
    return { text, status, priority, extra };
}
function itemToObject(item) {
    const out = { text: item.text };
    if (item.status !== 'open') {
        out.status = item.status;
    }
    if (item.priority) {
        out.priority = item.priority;
    }
    for (const [key, value] of Object.entries(item.extra)) {
        if (key === 'priority' && item.priority)
            continue;
        out[key] = value;
    }
    return out;
}
function isObject(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
//# sourceMappingURL=checklistCodec.js.map