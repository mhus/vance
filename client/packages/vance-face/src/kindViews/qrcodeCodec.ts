/**
 * Parser for `kind: qrcode` documents — the body carries a payload
 * (typically a URL) plus flat render options, in any of the three
 * storage forms:
 *
 *  - Markdown: `---`-fenced front matter (`kind: qrcode` + option
 *    lines), payload = body after the closing fence.
 *  - YAML: `$meta: { kind: qrcode }` mapping, payload = `content` key.
 *  - JSON: `$meta` wrapper object, payload = `content` key.
 *
 * A body that matches none of these shapes is treated as the bare
 * payload — the inline chat fence (```` ```qrcode ````) uses exactly
 * that form, and a stored document without front matter still renders.
 *
 * The server side is a name-only {@code KindHandler} (like `formula`):
 * there is no structural codec to mirror, so no parity harness — this
 * module is the single place the on-disk shape is interpreted.
 *
 * Spec: `specification/public/doc-kind-qrcode.md`.
 */
import { parseYamlBody, unwrapJsonMeta } from '@vance/shared';

export type QrEcc = 'low' | 'medium' | 'quartile' | 'high';

export interface QrCodeDoc {
    /** The text the QR symbol encodes — usually a URL. */
    payload: string;
    /** Caption rendered under the symbol (front-matter `label`). */
    label: string;
    /** Rendered canvas size in px (clamped 64–2048). */
    size: number;
    /** Quiet-zone margin in modules (clamped 0–16). */
    margin: number;
    /** Error-correction level. */
    ecc: QrEcc;
    /** Module colours (HTML hex). */
    dark: string;
    light: string;
}

export const QR_DEFAULTS: QrCodeDoc = {
    payload: '',
    label: '',
    size: 320,
    margin: 4,
    ecc: 'medium',
    dark: '#000000',
    light: '#ffffff',
};

const SIZE_MIN = 64;
const SIZE_MAX = 2048;
const MARGIN_MAX = 16;
const ECC_LEVELS: QrEcc[] = ['low', 'medium', 'quartile', 'high'];

/**
 * Parse a `kind: qrcode` body (any storage form) into a
 * {@link QrCodeDoc}. Lenient by design: unknown option values fall
 * back to their defaults instead of failing the render — a QR that
 * renders with default styling beats an error banner over a typo in
 * `ecc:`.
 */
export function parseQrCodeDoc(body: string | null | undefined): QrCodeDoc {
    const raw = (body ?? '').replace(/^\uFEFF/, '');
    const trimmedStart = raw.trimStart();

    if (trimmedStart.startsWith('---')) {
        return parseMarkdown(raw);
    }
    if (trimmedStart.startsWith('{')) {
        return fromObject(parseJsonBody(raw));
    }
    // YAML is only consulted when the body carries one of the YAML
    // form's unmistakable top-level keys — free-text payloads (a bare
    // URL, multi-line vCard, …) must never be fed through the YAML
    // parser, where a stray `key: value` line would silently turn the
    // payload into an options map.
    if (/^($meta|content):/m.test(trimmedStart)) {
        try {
            return fromObject(parseYamlBody(raw));
        } catch {
            // fall through: a `content:`-shaped line inside free text
            // is payload, not structure.
        }
    }
    return { ...QR_DEFAULTS, payload: raw.trim() };
}

/**
 * Overlay fence-language options (` ```qrcode size=512,ecc=high `)
 * onto a parsed document. Only known keys are applied, with the same
 * leniency as the document options.
 */
export function applyFenceMeta(
    doc: QrCodeDoc,
    meta: Record<string, string>,
): QrCodeDoc {
    return applyOptions(doc, meta);
}

// ── Markdown ─────────────────────────────────────────────────────

const FENCE = '---';

function parseMarkdown(body: string): QrCodeDoc {
    const lines = body.split('\n');
    let i = 1; // index 0 is the opening fence
    const header: Record<string, string> = {};
    while (i < lines.length && lines[i].trim() !== FENCE) {
        const line = lines[i].trim();
        if (line && !line.startsWith('#')) {
            const colon = line.indexOf(':');
            if (colon > 0) {
                header[line.slice(0, colon).trim()] = stripQuotes(line.slice(colon + 1).trim());
            }
        }
        i++;
    }
    const payload = i < lines.length
        ? lines.slice(i + 1).join('\n').trim()
        : '';
    return applyOptions({ ...QR_DEFAULTS, payload }, header);
}

// ── JSON / YAML ──────────────────────────────────────────────────

function parseJsonBody(body: string): Record<string, unknown> {
    try {
        const parsed = JSON.parse(body);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
            return unwrapJsonMeta(parsed as Record<string, unknown>);
        }
    } catch {
        // handled below: an unparseable body is still renderable as
        // raw payload text.
    }
    return {};
}

function fromObject(obj: Record<string, unknown>): QrCodeDoc {
    const payloadRaw = obj['content'];
    const payload = typeof payloadRaw === 'string' ? payloadRaw.trim() : '';
    return applyOptions({ ...QR_DEFAULTS, payload }, obj);
}

// ── Option normalisation ─────────────────────────────────────────

function applyOptions(
    base: QrCodeDoc,
    raw: Record<string, unknown>,
): QrCodeDoc {
    const out = { ...base };
    if (typeof raw['label'] === 'string') out.label = raw['label'].trim();
    out.size = clampInt(raw['size'], out.size, SIZE_MIN, SIZE_MAX);
    out.margin = clampInt(raw['margin'], out.margin, 0, MARGIN_MAX);
    out.ecc = eccOf(raw['ecc'], out.ecc);
    out.dark = hexOf(raw['dark'], out.dark);
    out.light = hexOf(raw['light'], out.light);
    return out;
}

function clampInt(
    raw: unknown,
    fallback: number,
    min: number,
    max: number,
): number {
    const n = typeof raw === 'number' ? raw : Number.parseInt(String(raw ?? ''), 10);
    if (!Number.isFinite(n)) return fallback;
    return Math.min(max, Math.max(min, Math.round(n)));
}

function eccOf(raw: unknown, fallback: QrEcc): QrEcc {
    const v = String(raw ?? '').trim().toLowerCase();
    return (ECC_LEVELS as string[]).includes(v) ? (v as QrEcc) : fallback;
}

/** Accepts `#rgb` / `#rrggbb` hex colours; anything else keeps the default. */
function hexOf(raw: unknown, fallback: string): string {
    const v = String(raw ?? '').trim();
    return /^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$/.test(v) ? v.toLowerCase() : fallback;
}

function stripQuotes(v: string): string {
    return v.replace(/^["']|["']$/g, '');
}
