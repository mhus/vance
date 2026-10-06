import * as yaml from 'js-yaml';

/**
 * Codec for `kind: vance-mount-source` documents — one mounted external tree
 * of the Jaglan mount system (`_vance/config/mounts/<id>.yaml`, spec
 * `specification/public/jaglan-system.md`; server truth:
 * `SourceConfigLoader` / `LocalFileJaglanProtocol`).
 *
 * Same whole-map contract as the research codec: the form owns exactly the
 * fields it renders (protocol, endpoint, credential, enabled, and the two
 * `local` extras {@code rootDir} / {@code writable}) and every other key —
 * `$meta`, `metadataTtlSeconds`, protocol fields this build does not know —
 * survives each form round-trip. Serialising rebuilds the YAML from that
 * map, so a form edit can never silently drop configuration the form does
 * not know about.
 */
export type MountSourceDoc = Record<string, unknown>;

/** The kind marker this form guarantees in `$meta` on every save. */
export const MOUNT_SOURCE_KIND = 'vance-mount-source';

/** Parse failure surfaced by {@link parseMountSourceDoc}. */
export class MountSourceParseError extends Error {}

/**
 * Protocols the server ships mount implementations for
 * (`jaglan/protocols/*`). The select offers exactly these; a document using
 * another id keeps it verbatim and the form shows the raw input instead of
 * pretending the value is one of the known ones.
 */
export const MOUNT_PROTOCOLS = ['local', 'demo', 'ode'] as const;

/** Form state — the fields the form owns. */
export interface MountSourceForm {
  /** Protocol id; free text, an unknown id is kept as written. */
  protocol: string;
  /** Service endpoint; empty for the {@code local} protocol. */
  baseUrl: string;
  /**
   * Credential: either a `{{secret:…}}` reference or a declared literal
   * (`{noop}…`) — the form stores what is typed, it never resolves either.
   */
  apiKey: string;
  enabled: boolean;
  /** Local directory the {@code local} protocol mounts. */
  rootDir: string;
  /** Whether the {@code local} protocol accepts writes and deletes. */
  writable: boolean;
}

/** Whether the form shows the local-directory fields ({@code local} only). */
export function showsLocalFields(protocol: string): boolean {
  return protocol === 'local';
}

/**
 * Parse a mount body. Throws {@link MountSourceParseError} when the YAML is
 * malformed or not a top-level mapping — the shell then falls back to the
 * raw YAML editor. The reserved `$meta` header is part of the map like any
 * other key and survives round-trips.
 */
export function parseMountSourceDoc(body: string): MountSourceDoc {
  // js-yaml v5 throws on an empty document instead of returning undefined —
  // a blank body is an empty mount map, not a parse error.
  if (body.trim() === '') return {};
  let parsed: unknown;
  try {
    parsed = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  } catch (e) {
    throw new MountSourceParseError(e instanceof Error ? e.message : String(e));
  }
  if (parsed === null || parsed === undefined) return {};
  if (typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new MountSourceParseError('a mount source document must be a YAML mapping');
  }
  return parsed as MountSourceDoc;
}

/** Serialise the model back to YAML. */
export function serializeMountSourceDoc(doc: MountSourceDoc): string {
  return yaml.dump(doc, {
    indent: 2,
    lineWidth: 120,
    noRefs: true,
    sortKeys: false,
  });
}

/** Keys the form owns — anything else in the map is preserved untouched. */
export const OWNED_KEYS = [
  'protocol',
  'baseUrl',
  'apiKey',
  'enabled',
  'rootDir',
  'writable',
] as const;

/** Count of keys the form does not render (shown as a "kept" notice). */
export function preservedKeyCount(doc: MountSourceDoc): number {
  return Object.keys(doc).filter(
    (k) => k !== '$meta' && !(OWNED_KEYS as readonly string[]).includes(k),
  ).length;
}

/** Derive the form state from the doc. Unknown values are kept verbatim. */
export function formFromDoc(doc: MountSourceDoc): MountSourceForm {
  return {
    protocol: stringOf(doc.protocol) ?? '',
    baseUrl: stringOf(doc.baseUrl) ?? '',
    apiKey: stringOf(doc.apiKey) ?? '',
    enabled: doc.enabled !== false,
    rootDir: stringOf(doc.rootDir) ?? '',
    writable: doc.writable === true,
  };
}

/**
 * Write the form state back into a **cloned** doc: the owned keys are set or
 * dropped, everything else — `$meta`, `metadataTtlSeconds`, unknown protocol
 * fields — passes through unchanged. `$meta.kind` is guaranteed, so a mount
 * that predates the kind routes through this form even before anybody edited
 * it here (the shell falls back to the row's kind when the body has none).
 *
 * `enabled` and `writable` are written explicitly in both directions: this
 * file is operator-facing configuration, where an explicit `writable: false`
 * reads better than a key whose absence is the switch.
 */
export function applyForm(doc: MountSourceDoc, form: MountSourceForm): MountSourceDoc {
  const out: MountSourceDoc = { ...doc };
  putOrDrop(out, 'protocol', form.protocol.trim());
  putOrDrop(out, 'baseUrl', form.baseUrl.trim());
  putOrDrop(out, 'apiKey', form.apiKey.trim());
  putOrDrop(out, 'rootDir', form.rootDir.trim());
  out.enabled = form.enabled;
  out.writable = form.writable;

  // $meta first, like the templates write it — and always carrying the kind
  // so the document keeps routing to this form.
  const meta: Record<string, unknown> =
    typeof out.$meta === 'object' && out.$meta !== null && !Array.isArray(out.$meta)
      ? { ...(out.$meta as Record<string, unknown>) }
      : {};
  meta.kind = MOUNT_SOURCE_KIND;
  delete out.$meta;
  return { $meta: meta, ...out };
}

function putOrDrop(out: MountSourceDoc, key: string, value: string): void {
  if (value === '') delete out[key];
  else out[key] = value;
}

function stringOf(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}
