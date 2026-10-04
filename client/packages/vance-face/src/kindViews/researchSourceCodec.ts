import * as yaml from 'js-yaml';

/**
 * Codec for `kind: vance-research-source` documents — one search source of
 * the Zarniwoop research dispatcher (spec `specification/public/zarniwoop-service.md`,
 * config as documents: `planning/archive/source-config-documents.md`).
 *
 * The parsed model is the **whole YAML map**, not a typed subset: the form
 * view owns only the fields it renders (protocol, endpoint, credential,
 * a few per-protocol extras) and every other key — `$meta`, `readerIdentity`,
 * protocol-specific fields this build does not know — survives each form
 * round-trip. Serialising rebuilds the YAML from that map, so a form edit can
 * never silently drop configuration the form does not know about.
 *
 * The server is the single source of truth for the format
 * (`SourceConfigLoader` keeps unknown keys the same way); this codec mirrors
 * its four known keys and never invents semantics of its own.
 */
export type ResearchSourceDoc = Record<string, unknown>;

/** The kind marker this form guarantees in `$meta` on every save. */
export const RESEARCH_SOURCE_KIND = 'vance-research-source';

/** Parse failure surfaced by {@link parseResearchSourceDoc}. */
export class ResearchSourceParseError extends Error {}

/**
 * Protocols the server ships search implementations for
 * (`zarniwoop/protocols/*`). The select offers exactly these; a document
 * using another id keeps it verbatim and the form shows the raw input
 * instead of pretending the value is one of the known ones.
 */
export const RESEARCH_PROTOCOLS = [
  'arxiv',
  'hackernews',
  'ode',
  'openalex',
  'openlibrary',
  'pubmed',
  'serper',
  'wikipedia',
] as const;

/** Form state — the fields the form owns. */
export interface ResearchSourceForm {
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
  /** Politeness contact — `openalex` / `pubmed`. */
  contactEmail: string;
  /** Capabilities cache TTL in seconds — `ode`. */
  capsTtlSeconds: string;
}


/**
 * Parse a source body. Throws {@link ResearchSourceParseError} when the YAML
 * is malformed or not a top-level mapping — the shell then falls back to the
 * raw YAML editor. The reserved `$meta` header is part of the map like any
 * other key and survives round-trips.
 */
export function parseResearchSourceDoc(body: string): ResearchSourceDoc {
  // js-yaml v5 throws on an empty document instead of returning
  // undefined — a blank body is an empty source map, not a parse error.
  if (body.trim() === '') return {};
  let parsed: unknown;
  try {
    parsed = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  } catch (e) {
    throw new ResearchSourceParseError(e instanceof Error ? e.message : String(e));
  }
  if (parsed === null || parsed === undefined) return {};
  if (typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new ResearchSourceParseError('a research source document must be a YAML mapping');
  }
  return parsed as ResearchSourceDoc;
}

/** Serialise the model back to YAML. */
export function serializeResearchSourceDoc(doc: ResearchSourceDoc): string {
  return yaml.dump(doc, {
    indent: 2,
    lineWidth: 120,
    noRefs: true,
    sortKeys: false,
  });
}

/** Whether the form shows the politeness-contact field ({@code openalex} / {@code pubmed}). */
export function showsContactEmail(protocol: string): boolean {
  return protocol === 'openalex' || protocol === 'pubmed';
}

/** Whether the form shows the capabilities TTL field (ode only). */
export function showsCapsTtl(protocol: string): boolean {
  return protocol === 'ode';
}

/** Keys the form owns — anything else in the map is preserved untouched. */
export const OWNED_KEYS = [
  'protocol',
  'baseUrl',
  'apiKey',
  'enabled',
  'contactEmail',
  'capsTtlSeconds',
] as const;

/** Count of keys the form does not render (shown as a "kept" notice). */
export function preservedKeyCount(doc: ResearchSourceDoc): number {
  return Object.keys(doc).filter(
    (k) => k !== '$meta' && !(OWNED_KEYS as readonly string[]).includes(k),
  ).length;
}

/** Derive the form state from the doc. Unknown values are kept verbatim. */
export function formFromDoc(doc: ResearchSourceDoc): ResearchSourceForm {
  return {
    protocol: stringOf(doc.protocol) ?? '',
    baseUrl: stringOf(doc.baseUrl) ?? '',
    apiKey: stringOf(doc.apiKey) ?? '',
    enabled: doc.enabled !== false,
    contactEmail: stringOf(doc.contactEmail) ?? '',
    capsTtlSeconds: stringOf(doc.capsTtlSeconds) ?? '',
  };
}

/**
 * Write the form state back into a **cloned** doc: the owned keys are set or
 * dropped, everything else — `$meta`, `readerIdentity`, unknown protocol
 * fields — passes through unchanged. `$meta.kind` is guaranteed, so a source
 * that predates the kind routes through this form even before anybody edited
 * it here (the shell falls back to the row's kind when the body has none).
 *
 * `enabled` is written explicitly in both directions: this file is
 * operator-facing configuration, where an explicit `enabled: false` reads
 * better than a key whose absence is the switch.
 */
export function applyForm(doc: ResearchSourceDoc, form: ResearchSourceForm): ResearchSourceDoc {
  const out: ResearchSourceDoc = { ...doc };
  putOrDrop(out, 'protocol', form.protocol.trim());
  putOrDrop(out, 'baseUrl', form.baseUrl.trim());
  putOrDrop(out, 'apiKey', form.apiKey.trim());
  putOrDrop(out, 'contactEmail', form.contactEmail.trim());
  putOrDrop(out, 'capsTtlSeconds', form.capsTtlSeconds.trim());
  out.enabled = form.enabled;

  // $meta first, like the templates write it — and always carrying the kind
  // so the document keeps routing to this form.
  const meta: Record<string, unknown> =
    typeof out.$meta === 'object' && out.$meta !== null && !Array.isArray(out.$meta)
      ? { ...(out.$meta as Record<string, unknown>) }
      : {};
  meta.kind = RESEARCH_SOURCE_KIND;
  delete out.$meta;
  return { $meta: meta, ...out };
}

function putOrDrop(out: ResearchSourceDoc, key: string, value: string): void {
  if (value === '') delete out[key];
  else out[key] = value;
}

function stringOf(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}