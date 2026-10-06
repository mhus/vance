import * as yaml from 'js-yaml';

/**
 * Codec for `kind: vance-model` documents — one operator-managed model
 * document of the {@code ModelCatalog} (`_vance/model/<provider>/<slug>.yaml`,
 * spec `specification/public/llm-resource-management.md` §3a).
 *
 * <p>Same whole-map contract as the other source-config codecs, with one
 * addition the catalog's deep-merge semantics force: a model document is
 * <b>legally partial</b> — an override that carries only
 * {@code contextWindowTokens} inherits everything else from the next outer
 * layer. The form therefore owns exactly the fields it renders and drops
 * them when empty (an absent key inherits; an explicit key overrides),
 * while every other key — {@code $meta}, {@code messageParser},
 * {@code unsupportedParams}, protocol fields this build does not know —
 * passes through untouched.
 *
 * <p>Server truth: {@code ModelCatalog} / {@code ModelInfo}. This codec
 * mirrors its known fields and never invents semantics of its own.
 */
export type ModelDoc = Record<string, unknown>;

/** The kind marker this form guarantees in `$meta` on every save. */
export const MODEL_SOURCE_KIND = 'vance-model';

/** Parse failure surfaced by {@link parseModelDoc}. */
export class ModelDocParseError extends Error {}

/** Sizes the catalog knows — {@code ModelSize}. */
export const MODEL_SIZES = ['SMALL', 'LARGE'] as const;

/** Capabilities the catalog knows — {@code ModelCapability}. */
export const MODEL_CAPABILITIES = ['VISION', 'PDF', 'THINKING', 'MID_CONVERSATION_SYSTEM'] as const;

/** Form state — the fields the form owns. */
export interface ModelDocForm {
  /** Display name, optional — absent inherits. */
  displayName: string;
  /**
   * Wire name; only needed when it cannot be derived from the filename
   * (Ollama tags like {@code qwen3:30b}). Absent ⇒ the path is the name.
   */
  wireName: string;
  contextWindowTokens: string;
  defaultMaxOutputTokens: string;
  size: string;
  /** Known capabilities, checked in the form. */
  capabilities: string[];
  timeoutSeconds: string;
  /** Pricing: the three known keys, edited inside the {@code pricing} map. */
  pricingCurrency: string;
  pricingInputPerMTok: string;
  pricingOutputPerMTok: string;
}

/**
 * Parse a model body. Throws {@link ModelDocParseError} when the YAML is
 * malformed or not a top-level mapping — the shell then falls back to the
 * raw YAML editor. An empty body is a legal (empty) override, not an error.
 */
export function parseModelDoc(body: string): ModelDoc {
  // js-yaml v5 throws on an empty document instead of returning undefined —
  // a blank body is an empty override map, not a parse error.
  if (body.trim() === '') return {};
  let parsed: unknown;
  try {
    parsed = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  } catch (e) {
    throw new ModelDocParseError(e instanceof Error ? e.message : String(e));
  }
  if (parsed === null || parsed === undefined) return {};
  if (typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new ModelDocParseError('a model document must be a YAML mapping');
  }
  return parsed as ModelDoc;
}

/** Serialise the model back to YAML. */
export function serializeModelDoc(doc: ModelDoc): string {
  return yaml.dump(doc, {
    indent: 2,
    lineWidth: 120,
    noRefs: true,
    sortKeys: false,
  });
}

/** Keys the form owns at the top level — anything else is preserved untouched. */
export const OWNED_KEYS = [
  'displayName',
  'wireName',
  'contextWindowTokens',
  'defaultMaxOutputTokens',
  'size',
  'capabilities',
  'timeoutSeconds',
  'pricing',
] as const;

/** Count of keys the form does not render (shown as a "kept" notice). */
export function preservedKeyCount(doc: ModelDoc): number {
  const pricingExtras = pricingMap(doc) ? preservedPricingKeys(doc).length : 0;
  return Object.keys(doc).filter(
    (k) => k !== '$meta' && !(OWNED_KEYS as readonly string[]).includes(k),
  ).length + pricingExtras;
}

/** Derive the form state from the doc. Unknown values are kept verbatim. */
export function formFromDoc(doc: ModelDoc): ModelDocForm {
  const pricing = pricingMap(doc) ?? {};
  return {
    displayName: stringOf(doc.displayName) ?? '',
    wireName: stringOf(doc.wireName) ?? '',
    contextWindowTokens: stringOf(doc.contextWindowTokens) ?? '',
    defaultMaxOutputTokens: stringOf(doc.defaultMaxOutputTokens) ?? '',
    size: stringOf(doc.size) ?? '',
    capabilities: Array.isArray(doc.capabilities)
      ? doc.capabilities.map(String)
      : [],
    timeoutSeconds: stringOf(doc.timeoutSeconds) ?? '',
    pricingCurrency: stringOf(pricing.currency) ?? '',
    pricingInputPerMTok: stringOf(pricing.inputPerMTok) ?? '',
    pricingOutputPerMTok: stringOf(pricing.outputPerMTok) ?? '',
  };
}

/**
 * Write the form state back into a **cloned** doc. Owned top-level keys are
 * set or dropped (absent = inherit), unknown keys pass through, and the
 * {@code pricing} map merges the three owned keys into whatever else it
 * carries. `$meta.kind` is guaranteed, so a model written before the kind
 * existed routes through this form.
 */
export function applyForm(doc: ModelDoc, form: ModelDocForm): ModelDoc {
  const out: ModelDoc = { ...doc };
  putOrDrop(out, 'displayName', form.displayName.trim());
  putOrDrop(out, 'wireName', form.wireName.trim());
  putNumberOrDrop(out, 'contextWindowTokens', form.contextWindowTokens.trim());
  putNumberOrDrop(out, 'defaultMaxOutputTokens', form.defaultMaxOutputTokens.trim());
  putOrDrop(out, 'size', form.size.trim());
  putNumberOrDrop(out, 'timeoutSeconds', form.timeoutSeconds.trim());
  if (form.capabilities.length === 0) {
    delete out.capabilities;
  } else {
    out.capabilities = [...form.capabilities];
  }
  applyPricing(out, form);

  // $meta first, like the templates write it — and always carrying the kind
  // so the document keeps routing to this form.
  const meta: Record<string, unknown> =
    typeof out.$meta === 'object' && out.$meta !== null && !Array.isArray(out.$meta)
      ? { ...(out.$meta as Record<string, unknown>) }
      : {};
  meta.kind = MODEL_SOURCE_KIND;
  delete out.$meta;
  return { $meta: meta, ...out };
}

/**
 * Merge the three owned pricing keys into the {@code pricing} map — unknown
 * pricing keys (a future {@code costPerImage} etc.) survive. When nothing
 * priced remains and nothing else was there, the whole key drops: absent
 * inherits.
 */
function applyPricing(out: ModelDoc, form: ModelDocForm): void {
  const existing = pricingMap(out);
  const pricing: Record<string, unknown> = { ...(existing ?? {}) };
  putOrDrop(pricing, 'currency', form.pricingCurrency.trim());
  putNumberOrDrop(pricing, 'inputPerMTok', form.pricingInputPerMTok.trim());
  putNumberOrDrop(pricing, 'outputPerMTok', form.pricingOutputPerMTok.trim());
  if (Object.keys(pricing).length === 0) {
    delete out.pricing;
  } else {
    out.pricing = pricing;
  }
}

function pricingMap(doc: ModelDoc): Record<string, unknown> | null {
  const p = doc.pricing;
  return typeof p === 'object' && p !== null && !Array.isArray(p)
    ? p as Record<string, unknown>
    : null;
}

/** Pricing keys the form does not render — part of the "kept" notice. */
const OWNED_PRICING_KEYS = ['currency', 'inputPerMTok', 'outputPerMTok'] as const;

function preservedPricingKeys(doc: ModelDoc): string[] {
  const pricing = pricingMap(doc) ?? {};
  return Object.keys(pricing).filter(
    (k) => !(OWNED_PRICING_KEYS as readonly string[]).includes(k),
  );
}

function putOrDrop(out: Record<string, unknown>, key: string, value: string): void {
  if (value === '') delete out[key];
  else out[key] = value;
}

/**
 * Numeric owned fields are written as YAML numbers, not digit strings —
 * the catalog (and the kind handler's type check) reads
 * {@code contextWindowTokens: '131072'} as a string and silently falls back
 * to the default. A value that does not parse as a number is kept as
 * written: the handler reports it, the form does not invent a number.
 */
function putNumberOrDrop(out: Record<string, unknown>, key: string, value: string): void {
  if (value === '') {
    delete out[key];
    return;
  }
  const num = Number(value);
  if (Number.isFinite(num) && /^-?\d+(\.\d+)?$/.test(value)) {
    out[key] = num;
  } else {
    out[key] = value;
  }
}

function stringOf(v: unknown): string | null {
  if (typeof v === 'string') return v;
  if (typeof v === 'number') return String(v);
  return null;
}
