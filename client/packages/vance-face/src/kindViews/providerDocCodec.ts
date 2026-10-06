import * as yaml from 'js-yaml';

/**
 * Codec for `kind: vance-model-provider` documents — the
 * {@code _provider.yaml} sidecar of one provider instance in the
 * {@code ModelCatalog} tree (`_vance/model/<provider>/_provider.yaml`, spec
 * {@code specification/public/llm-resource-management.md} §3a). The sidecar
 * is what makes a provider instance usable: it declares the wire protocol
 * once, so {@code provider:<model>} resolves without a per-name setting.
 *
 * <p>Same whole-map contract as the model codec: the form owns exactly the
 * fields it renders (display name, wire type, auth type, base URL) and every
 * other key — {@code $meta}, provider-specific fields this build does not
 * know — passes through untouched.
 */
export type ProviderDoc = Record<string, unknown>;

/** The kind marker this form guarantees in `$meta` on every save. */
export const PROVIDER_SOURCE_KIND = 'vance-model-provider';

/** Parse failure surfaced by {@link parseProviderDoc}. */
export class ProviderDocParseError extends Error {}

/**
 * Wire protocols the server ships mappers for (values of {@code wireType}).
 * The select offers exactly these; a document using another id keeps it
 * verbatim and the form shows the raw input instead of pretending the value
 * is one of the known ones.
 */
export const WIRE_TYPES = ['openai', 'anthropic', 'gemini', 'ollama', 'lmstudio', 'local'] as const;

/** Credential shapes the resolver knows (values of {@code authType}). */
export const AUTH_TYPES = ['api-key', 'none', 'oauth'] as const;

/** Form state — the fields the form owns. */
export interface ProviderDocForm {
  displayName: string;
  /** Wire protocol id; free text, an unknown id is kept as written. */
  wireType: string;
  /** Credential shape; free text, an unknown id is kept as written. */
  authType: string;
  /** Service endpoint, optional — absent inherits from settings/defaults. */
  baseUrl: string;
}

/**
 * Parse a provider sidecar. Throws {@link ProviderDocParseError} when the
 * YAML is malformed or not a top-level mapping — the shell then falls back
 * to the raw YAML editor. An empty body is a legal (empty) sidecar, not an
 * error.
 */
export function parseProviderDoc(body: string): ProviderDoc {
  // js-yaml v5 throws on an empty document instead of returning undefined —
  // a blank body is an empty sidecar map, not a parse error.
  if (body.trim() === '') return {};
  let parsed: unknown;
  try {
    parsed = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  } catch (e) {
    throw new ProviderDocParseError(e instanceof Error ? e.message : String(e));
  }
  if (parsed === null || parsed === undefined) return {};
  if (typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new ProviderDocParseError('a provider sidecar must be a YAML mapping');
  }
  return parsed as ProviderDoc;
}

/** Serialise the sidecar back to YAML. */
export function serializeProviderDoc(doc: ProviderDoc): string {
  return yaml.dump(doc, {
    indent: 2,
    lineWidth: 120,
    noRefs: true,
    sortKeys: false,
  });
}

/** Keys the form owns — anything else in the map is preserved untouched. */
export const OWNED_KEYS = ['displayName', 'wireType', 'authType', 'baseUrl'] as const;

/** Count of keys the form does not render (shown as a "kept" notice). */
export function preservedKeyCount(doc: ProviderDoc): number {
  return Object.keys(doc).filter(
    (k) => k !== '$meta' && !(OWNED_KEYS as readonly string[]).includes(k),
  ).length;
}

/** Derive the form state from the doc. Unknown values are kept verbatim. */
export function formFromDoc(doc: ProviderDoc): ProviderDocForm {
  return {
    displayName: stringOf(doc.displayName) ?? '',
    wireType: stringOf(doc.wireType) ?? '',
    authType: stringOf(doc.authType) ?? '',
    baseUrl: stringOf(doc.baseUrl) ?? '',
  };
}

/**
 * Write the form state back into a **cloned** doc: the owned keys are set or
 * dropped (absent = inherit), everything else — {@code $meta}, unknown
 * provider fields — passes through unchanged. `$meta.kind` is guaranteed,
 * so a sidecar written before the kind existed routes through this form.
 */
export function applyForm(doc: ProviderDoc, form: ProviderDocForm): ProviderDoc {
  const out: ProviderDoc = { ...doc };
  putOrDrop(out, 'displayName', form.displayName.trim());
  putOrDrop(out, 'wireType', form.wireType.trim());
  putOrDrop(out, 'authType', form.authType.trim());
  putOrDrop(out, 'baseUrl', form.baseUrl.trim());

  // $meta first, like the templates write it — and always carrying the kind
  // so the document keeps routing to this form.
  const meta: Record<string, unknown> =
    typeof out.$meta === 'object' && out.$meta !== null && !Array.isArray(out.$meta)
      ? { ...(out.$meta as Record<string, unknown>) }
      : {};
  meta.kind = PROVIDER_SOURCE_KIND;
  delete out.$meta;
  return { $meta: meta, ...out };
}

function putOrDrop(out: Record<string, unknown>, key: string, value: string): void {
  if (value === '') delete out[key];
  else out[key] = value;
}

function stringOf(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}
