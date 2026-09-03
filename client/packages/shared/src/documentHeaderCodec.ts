// Shared helpers for the `$meta` header convention used by every
// kind-codec for both JSON and YAML.
//
// Server-side strategies live in
// `vance-shared/document/{Json,Yaml,Markdown}HeaderStrategy.java` —
// these client helpers mirror their on-disk shape so that
// `DocumentDocument.kind` gets correctly mirrored on save.
//
// JSON and YAML are symmetric: both carry a top-level `$meta`
// mapping with `kind` (plus optional scalar extras) at the head of
// the body keys.

import * as yaml from 'js-yaml';

const META_KEY = '$meta';

/**
 * How much of a body is looked at when sniffing its kind. The header sits at
 * the head by construction (`$meta` first, front matter before anything
 * else), so a bounded slice is not an approximation — it is the whole
 * question. It also keeps this off the critical path for a large document,
 * where parsing megabytes of YAML to read one word would be felt.
 */
const KIND_PROBE_CHARS = 8192;

/**
 * The `kind` a body declares, or `null` when it declares none.
 *
 * <p>Mirror of the server's header strategies (`{Json,Yaml,Markdown}HeaderStrategy`)
 * for the one field a client needs before the server has had a chance to
 * persist it: a **mounted** document's row carries no kind until something
 * reads its body, and a reader that has the body already should not have to
 * wait for a round trip to find out what it is looking at.
 *
 * <p>Deliberately narrow — one field, head only, never throws. Anything
 * richer belongs to the codecs, which parse the whole document anyway once
 * the kind has selected them.
 */
export function readKindFromBody(
  body: string | null | undefined,
  mimeType?: string | null,
): string | null {
  const head = (body ?? '').slice(0, KIND_PROBE_CHARS);
  if (!head.trim()) return null;
  const mime = (mimeType ?? '').toLowerCase();
  try {
    if (mime.includes('json') || head.trimStart().startsWith('{')) {
      const m = /"\$meta"\s*:\s*\{[^}]*?"kind"\s*:\s*"([^"]+)"/.exec(head);
      if (m) return m[1];
    }
    if (mime.includes('yaml') || mime.includes('yml')) {
      // Only the `$meta` mapping, and only its `kind` — a top-level `kind:`
      // elsewhere in the document is a field of that document, not its type.
      const m = /(?:^|\n)\$meta:[^\S\n]*\n(?:[^\S\n]+[^\n]*\n)*?[^\S\n]+kind:[^\S\n]*(\S+)/.exec(head);
      if (m) return stripQuotes(m[1]);
    }
    if (mime.includes('markdown') || head.startsWith('---')) {
      const fm = /^---[^\S\n]*\n([\s\S]*?)\n---[^\S\n]*(?:\n|$)/.exec(head);
      if (fm) {
        const m = /(?:^|\n)kind:[^\S\n]*(\S+)/.exec(fm[1]);
        if (m) return stripQuotes(m[1]);
      }
    }
  } catch {
    // A malformed body has no kind — same answer the server gives.
  }
  return null;
}

function stripQuotes(raw: string): string {
  return raw.replace(/^["']|["'],?$/g, '').replace(/,$/, '');
}

/**
 * Lift a JSON object out of the {@code $meta} wrapper. If the
 * caller's object has a {@code $meta} key whose value is an object,
 * its scalar entries are merged on top of the body keys.
 *
 * Non-scalar {@code $meta} values are dropped — they wouldn't survive
 * a round-trip through the server's {@code JsonHeaderStrategy} either.
 */
export function unwrapJsonMeta(obj: Record<string, unknown>): Record<string, unknown> {
  const metaVal = obj[META_KEY];
  if (!isObject(metaVal)) return obj;
  const { [META_KEY]: _drop, ...rest } = obj;
  const merged: Record<string, unknown> = { ...rest };
  for (const [k, v] of Object.entries(metaVal)) {
    if (isScalar(v)) {
      merged[k] = v;
    }
  }
  return merged;
}

/**
 * Build a JSON object with {@code $meta} wrapping the given
 * {@code kind}. Body keys live at the top level alongside
 * {@code $meta}.
 */
export function wrapJsonMeta(kind: string, body: Record<string, unknown>): Record<string, unknown> {
  return {
    [META_KEY]: { kind },
    ...body,
  };
}

/**
 * Parse a single-document YAML body and unwrap its {@code $meta}
 * mapping. Returns a flattened object that the kind-specific
 * {@code promoteTo…Document} can consume directly — scalar
 * {@code $meta} entries (kind, schema, …) land at the top level next
 * to the body keys.
 *
 * The top-level YAML value must be a mapping; anything else (sequence,
 * scalar) raises {@link Error}. The caller wraps with the codec's own
 * error type.
 */
export function parseYamlBody(body: string): Record<string, unknown> {
  const root = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  if (root === null || root === undefined) return {};
  if (!isObject(root)) {
    throw new Error('Top-level YAML must be a mapping');
  }
  return unwrapJsonMeta(root as Record<string, unknown>);
}

/**
 * Emit a single-document YAML body with {@code $meta} as the first
 * top-level key carrying {@code kind} (plus any optional scalar
 * extras). Shape mirrors {@code wrapJsonMeta} exactly.
 */
export function dumpYamlBody(
  kind: string,
  body: Record<string, unknown>,
  headerExtra?: Record<string, unknown>,
): string {
  const meta: Record<string, unknown> = { kind };
  if (headerExtra) {
    for (const [k, v] of Object.entries(headerExtra)) {
      if (k !== 'kind' && isScalar(v)) meta[k] = v;
    }
  }
  const wrapped: Record<string, unknown> = {
    [META_KEY]: meta,
    ...body,
  };
  return yaml.dump(wrapped, { indent: 2, lineWidth: 100, noRefs: true });
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function isScalar(v: unknown): boolean {
  return v === null
    || typeof v === 'string'
    || typeof v === 'number'
    || typeof v === 'boolean';
}
