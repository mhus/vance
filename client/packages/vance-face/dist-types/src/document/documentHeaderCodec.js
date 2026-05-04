// Shared helpers for the JSON `$meta` and YAML multi-document
// header conventions used by every kind-codec.
//
// Server-side strategies live in
// `vance-shared/document/{Json,Yaml,Markdown}HeaderStrategy.java` —
// these client helpers must mirror their on-disk shape so that
// `DocumentDocument.kind` gets correctly mirrored on save.
//
// Read-side helpers are tolerant: legacy single-doc YAML and
// top-level `kind` JSON keep working, the codec only normalizes on
// write.
import yaml from 'js-yaml';
const META_KEY = '$meta';
/**
 * Lift a JSON object out of the {@code $meta} wrapper. If the
 * caller's object has a {@code $meta} key whose value is an object,
 * its scalar entries are merged on top of the body keys (with
 * {@code $meta.kind} winning over a legacy top-level {@code kind}).
 *
 * Non-scalar {@code $meta} values are dropped — they wouldn't survive
 * a round-trip through the server's {@code JsonHeaderStrategy} either.
 */
export function unwrapJsonMeta(obj) {
    const metaVal = obj[META_KEY];
    if (!isObject(metaVal))
        return obj;
    const { [META_KEY]: _drop, ...rest } = obj;
    const merged = { ...rest };
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
export function wrapJsonMeta(kind, body) {
    return {
        [META_KEY]: { kind },
        ...body,
    };
}
/**
 * Parse a YAML body that may be in multi-document form (header
 * mapping, then `---`, then body) or in legacy single-document form
 * (everything in one mapping). Returns a flattened object that the
 * kind-specific {@code promoteTo…Document} can consume directly.
 *
 * Throws {@link Error} on parse failures — caller wraps with the
 * codec's own error type.
 */
export function mergeYamlMultiDoc(body) {
    const docs = yaml.loadAll(body, undefined, { schema: yaml.JSON_SCHEMA });
    if (docs.length === 0)
        return {};
    // Multi-document with a header mapping that carries `kind:` and a
    // distinct body document — normalise into one merged object so
    // the legacy promote-functions don't need to change shape.
    if (docs.length >= 2 && isObject(docs[0]) && typeof docs[0].kind === 'string') {
        const header = docs[0];
        const bodyDoc = docs[1];
        const merged = { ...header };
        if (isObject(bodyDoc)) {
            for (const [k, v] of Object.entries(bodyDoc)) {
                merged[k] = v;
            }
        }
        else if (Array.isArray(bodyDoc)) {
            // Pure-array body — interpret as the items/nodes array. The
            // codec-specific promote function decides which key it slots
            // into; we expose both common keys.
            merged.items = bodyDoc;
            merged.nodes = bodyDoc;
        }
        return merged;
    }
    if (docs.length >= 1 && isObject(docs[0])) {
        return docs[0];
    }
    throw new Error('Top-level YAML must be a mapping');
}
/**
 * Emit a YAML multi-document body where doc 1 is the header
 * (containing the {@code kind} and any other scalar metadata), and
 * doc 2 is the body mapping. Both halves are dumped with the same
 * formatting options so the result is internally consistent.
 */
export function dumpYamlMultiDoc(kind, body, headerExtra) {
    const header = { kind };
    if (headerExtra) {
        for (const [k, v] of Object.entries(headerExtra)) {
            if (isScalar(v) && k !== 'kind')
                header[k] = v;
        }
    }
    const opts = { indent: 2, lineWidth: 100, noRefs: true };
    return yaml.dump(header, opts) + '---\n' + yaml.dump(body, opts);
}
function isObject(v) {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}
function isScalar(v) {
    return v === null
        || typeof v === 'string'
        || typeof v === 'number'
        || typeof v === 'boolean';
}
//# sourceMappingURL=documentHeaderCodec.js.map