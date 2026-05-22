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

import yaml from 'js-yaml';

const META_KEY = '$meta';

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
