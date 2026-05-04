/**
 * Lift a JSON object out of the {@code $meta} wrapper. If the
 * caller's object has a {@code $meta} key whose value is an object,
 * its scalar entries are merged on top of the body keys (with
 * {@code $meta.kind} winning over a legacy top-level {@code kind}).
 *
 * Non-scalar {@code $meta} values are dropped — they wouldn't survive
 * a round-trip through the server's {@code JsonHeaderStrategy} either.
 */
export declare function unwrapJsonMeta(obj: Record<string, unknown>): Record<string, unknown>;
/**
 * Build a JSON object with {@code $meta} wrapping the given
 * {@code kind}. Body keys live at the top level alongside
 * {@code $meta}.
 */
export declare function wrapJsonMeta(kind: string, body: Record<string, unknown>): Record<string, unknown>;
/**
 * Parse a YAML body that may be in multi-document form (header
 * mapping, then `---`, then body) or in legacy single-document form
 * (everything in one mapping). Returns a flattened object that the
 * kind-specific {@code promoteTo…Document} can consume directly.
 *
 * Throws {@link Error} on parse failures — caller wraps with the
 * codec's own error type.
 */
export declare function mergeYamlMultiDoc(body: string): Record<string, unknown>;
/**
 * Emit a YAML multi-document body where doc 1 is the header
 * (containing the {@code kind} and any other scalar metadata), and
 * doc 2 is the body mapping. Both halves are dumped with the same
 * formatting options so the result is internally consistent.
 */
export declare function dumpYamlMultiDoc(kind: string, body: Record<string, unknown>, headerExtra?: Record<string, unknown>): string;
//# sourceMappingURL=documentHeaderCodec.d.ts.map