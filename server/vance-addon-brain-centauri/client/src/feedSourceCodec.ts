import * as yaml from 'js-yaml';

/**
 * Codec for `kind: vance-feed-source` documents — one feed endpoint of the
 * Centauri feed reader (`_vance/config/feeds/<id>.yaml`, spec
 * `specification/public/centauri-service.md`; server truth:
 * `SourceConfigLoader` / `FeedSourceFactory`).
 *
 * Same whole-map contract as the host's research codec: the form owns
 * exactly the fields it renders (protocol, endpoint, credential, enabled)
 * and every other key — `$meta`, `readerIdentity`, protocol fields this
 * build does not know — survives each form round-trip. Serialising
 * rebuilds the YAML from that map, so a form edit can never silently drop
 * configuration the form does not know about.
 */
export type FeedSourceDoc = Record<string, unknown>;

/** The kind marker this form guarantees in `$meta` on every save. */
export const FEED_SOURCE_KIND = 'vance-feed-source';

/** Parse failure surfaced by {@link parseFeedSourceDoc}. */
export class FeedSourceParseError extends Error {}

/**
 * Feed protocols this addon ships implementations for (the brain's `ode`
 * plus the examples here: `usgs`, `wikipedia`). The select offers exactly
 * these; a document using another id keeps it verbatim and the form shows
 * the raw input instead of pretending the value is one of the known ones.
 */
export const FEED_PROTOCOLS = ['ode', 'usgs', 'wikipedia'] as const;

/** Form state — the fields the form owns. */
export interface FeedSourceForm {
  /** Protocol id; free text, an unknown id is kept as written. */
  protocol: string;
  /** Service endpoint. */
  baseUrl: string;
  /**
   * Credential: either a `{{secret:…}}` reference or a declared literal
   * (`{noop}…`) — the form stores what is typed, it never resolves either.
   */
  apiKey: string;
  enabled: boolean;
}

/**
 * Parse a feed body. Throws {@link FeedSourceParseError} when the YAML is
 * malformed or not a top-level mapping — the shell then falls back to the
 * raw YAML editor. The reserved `$meta` header is part of the map like any
 * other key and survives round-trips.
 */
export function parseFeedSourceDoc(body: string): FeedSourceDoc {
  // js-yaml v5 throws on an empty document instead of returning undefined —
  // a blank body is an empty feed map, not a parse error.
  if (body.trim() === '') return {};
  let parsed: unknown;
  try {
    parsed = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  } catch (e) {
    throw new FeedSourceParseError(e instanceof Error ? e.message : String(e));
  }
  if (parsed === null || parsed === undefined) return {};
  if (typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new FeedSourceParseError('a feed source document must be a YAML mapping');
  }
  return parsed as FeedSourceDoc;
}

/** Serialise the model back to YAML. */
export function serializeFeedSourceDoc(doc: FeedSourceDoc): string {
  return yaml.dump(doc, {
    indent: 2,
    lineWidth: 120,
    noRefs: true,
    sortKeys: false,
  });
}

/** Keys the form owns — anything else in the map is preserved untouched. */
export const OWNED_KEYS = ['protocol', 'baseUrl', 'apiKey', 'enabled'] as const;

/** Count of keys the form does not render (shown as a "kept" notice). */
export function preservedKeyCount(doc: FeedSourceDoc): number {
  return Object.keys(doc).filter(
    (k) => k !== '$meta' && !(OWNED_KEYS as readonly string[]).includes(k),
  ).length;
}

/** Derive the form state from the doc. Unknown values are kept verbatim. */
export function formFromDoc(doc: FeedSourceDoc): FeedSourceForm {
  return {
    protocol: stringOf(doc.protocol) ?? '',
    baseUrl: stringOf(doc.baseUrl) ?? '',
    apiKey: stringOf(doc.apiKey) ?? '',
    enabled: doc.enabled !== false,
  };
}

/**
 * Write the form state back into a **cloned** doc: the owned keys are set or
 * dropped, everything else — `$meta`, `readerIdentity`, unknown protocol
 * fields — passes through unchanged. `$meta.kind` is guaranteed, so a feed
 * written before the kind existed routes through this form even before
 * anybody edited it here (the shell falls back to the row's kind when the
 * body has none).
 *
 * `enabled` is written explicitly in both directions: this file is
 * operator-facing configuration, where an explicit `enabled: false` reads
 * better than a key whose absence is the switch.
 */
export function applyForm(doc: FeedSourceDoc, form: FeedSourceForm): FeedSourceDoc {
  const out: FeedSourceDoc = { ...doc };
  putOrDrop(out, 'protocol', form.protocol.trim());
  putOrDrop(out, 'baseUrl', form.baseUrl.trim());
  putOrDrop(out, 'apiKey', form.apiKey.trim());
  out.enabled = form.enabled;

  // $meta first, like the templates write it — and always carrying the kind
  // so the document keeps routing to this form.
  const meta: Record<string, unknown> =
    typeof out.$meta === 'object' && out.$meta !== null && !Array.isArray(out.$meta)
      ? { ...(out.$meta as Record<string, unknown>) }
      : {};
  meta.kind = FEED_SOURCE_KIND;
  delete out.$meta;
  return { $meta: meta, ...out };
}

function putOrDrop(out: FeedSourceDoc, key: string, value: string): void {
  if (value === '') delete out[key];
  else out[key] = value;
}

function stringOf(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}
